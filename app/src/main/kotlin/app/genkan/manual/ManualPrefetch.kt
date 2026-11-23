package app.genkan.manual

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadQueue
import app.genkan.download.DownloadState
import app.genkan.home.LauncherPresence
import app.genkan.library.GameEntity
import app.genkan.library.LibraryDao
import app.genkan.library.LibrarySync
import app.genkan.library.LibraryViewSettings
import app.genkan.library.SyncStatus
import app.genkan.romm.RomMSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Fetches the manuals of installed games into [Manuals]' folder in the background, so they open
 * offline: after each library sync, and when a download finishes. Installed means a download in
 * state INSTALLED whose file is on the device, of a game with a manual on the server; nothing is
 * fetched for any other game. One at a time, signed in, not on a metered connection, held while
 * Genkan isn't in front; a cached manual gets its daily check instead, never replaced while open.
 * The folder keeps its own house on each pass: manuals of games gone from the library go, and over
 * [CAP_BYTES] manuals of games that aren't installed go, least recently opened first. Off with
 * Settings > Library & account > Fetch manuals of installed games.
 */
@Singleton
class ManualPrefetch @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manuals: Manuals,
    private val sync: LibrarySync,
    private val dao: LibraryDao,
    private val downloads: DownloadQueue,
    private val settings: LibraryViewSettings,
    private val launcher: LauncherPresence,
    private val session: RomMSession,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()

    fun start() {
        // A manual opened by hand is always fetched; the folder is brought back under its cap after.
        manuals.onStored = { scope.launch { manuals.trim(installedIds(), CAP_BYTES) } }
        scope.launch {
            val synced = sync.status.filterIsInstance<SyncStatus.Done>().map { true }
            // A game whose download finishes gets its manual then.
            val installed = downloads.downloads.map { all -> all.values.count { it.state == DownloadState.INSTALLED } }
                .distinctUntilChanged().drop(1).filter { it > 0 }.map { false }
            merge(synced, installed).collect { afterSync -> scope.launch { pass(afterSync) } }
        }
    }

    /** The installed games' ids: a download INSTALLED with its file on the device. */
    private fun installedIds(): Set<Long> = installed(downloads.downloads.value.values).toSet()

    /** A pass; [afterSync] when a finished sync started it, the only time games gone from the library are known. */
    private suspend fun pass(afterSync: Boolean) = running.withLock {
        if (!settings.prefetchManuals.first() || session.currentAccount() == null) return@withLock
        housekeep(afterSync, dao.allGames().map { it.id }.toSet(), manuals::removeGone)
        val games = installedIds().mapNotNull { dao.game(it) }
        val started = System.currentTimeMillis()
        val fetched = prefetchAll(
            games,
            awaitFree = { launcher.inFront.first { it } },
            metered = ::metered,
            check = { manuals.refresh(it) },
            cached = { manuals.cached(it) != null },
            fetch = { manuals.prefetch(it, MAX_BYTES) },
            full = { manuals.size() >= CAP_BYTES },
            trim = { manuals.trim(installedIds(), CAP_BYTES) },
        )
        Log.i(TAG, "Manuals of installed games: $fetched fetched of ${games.count { it.manualPath != null }} in ${System.currentTimeMillis() - started} ms; the folder holds ${manuals.size() / (1L shl 20)} MB")
    }

    private fun metered(): Boolean = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    companion object {
        private const val TAG = "ManualPrefetch"
        /** The manual folder's cap: about six times the installed games' manuals now, room to grow. */
        const val CAP_BYTES = 300L shl 20
        /** The largest manual fetched ahead; a larger one is still fetched when opened. */
        const val MAX_BYTES = 64L shl 20

        /**
         * Removes the manuals of games gone from the library ([libraryIds]), but only in a pass a
         * finished sync started, and never for an empty list (a sync that brought nothing, a database
         * rebuilt, a new sign-in before its first sync): that would remove every manual.
         */
        fun housekeep(afterSync: Boolean, libraryIds: Set<Long>, removeGone: (Set<Long>) -> Unit) {
            if (afterSync && libraryIds.isNotEmpty()) removeGone(libraryIds)
        }

        /** The ids of [all]'s downloads that are installed with their file on the device. */
        fun installed(all: Collection<DownloadEntity>): List<Long> =
            all.filter { it.state == DownloadState.INSTALLED && File(it.path).exists() }.map { it.romId }

        /**
         * The pass over [games], one at a time: each waits on [awaitFree] (Genkan in front); a metered
         * connection stops it; a cached manual gets its [check]; one not cached is fetched, after a
         * [trim] when the folder is [full], and the pass stops when it's still full. Returns how many
         * were fetched.
         */
        suspend fun prefetchAll(
            games: List<GameEntity>,
            awaitFree: suspend () -> Unit,
            metered: () -> Boolean,
            check: suspend (GameEntity) -> Unit,
            cached: (GameEntity) -> Boolean,
            fetch: suspend (GameEntity) -> Boolean,
            full: () -> Boolean,
            trim: () -> Unit,
        ): Int {
            var fetched = 0
            for (game in games.filter { it.manualPath != null }) {
                coroutineContext.ensureActive()
                awaitFree()
                if (metered()) break
                if (cached(game)) {
                    check(game)
                    continue
                }
                if (full()) {
                    trim()
                    if (full()) break
                }
                if (fetch(game)) fetched++
            }
            return fetched
        }
    }
}
