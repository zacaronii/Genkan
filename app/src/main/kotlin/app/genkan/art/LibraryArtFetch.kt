package app.genkan.art

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import app.genkan.home.LauncherPresence
import app.genkan.library.LibraryDao
import app.genkan.library.LibrarySync
import app.genkan.library.LibraryViewSettings
import app.genkan.library.SyncStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * After each library sync, fetches what the shelf draws for every game in the library (cover,
 * spine, logo, disc or cartridge, and the box's front and back with the full box) onto the disk,
 * one at a time on [ArtLoaders.background], in the library's order. It never gets in the way: it
 * holds while Genkan isn't in front (a game or another app has the top screen) and while the
 * opened shelf's own fetch ahead runs ([ShelfPrefetch.busy]), skips what was done within the day
 * without reading it, stops when the cache is [FILL_LIMIT] full (from then on only the opened
 * shelf's fetch does the daily check) or after [FAILURES_IN_A_ROW] failures in a row (the server
 * is down), and does nothing on a metered connection or signed out. A new sync starts it over:
 * syncs come when Genkan's process starts signed in, at a new sign-in, and from Sync in Settings
 * or Y on the library.
 */
@Singleton
class LibraryArtFetch @Inject constructor(
    @ApplicationContext private val context: Context,
    private val loaders: ArtLoaders,
    private val sync: LibrarySync,
    private val dao: LibraryDao,
    private val shelf: ShelfPrefetch,
    private val viewSettings: LibraryViewSettings,
    private val launcher: LauncherPresence,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            sync.status.filterIsInstance<SyncStatus.Done>().collectLatest {
                delay(AFTER_SYNC_MS)
                if (metered() || !loaders.signedIn()) return@collectLatest
                val paths = ShelfPrefetch.pathsFrom(dao.allGames(), 0, viewSettings.fullBox.first())
                val started = System.currentTimeMillis()
                val fetched = fetchAll(
                    paths,
                    held = { !launcher.inFront.value || shelf.busy() },
                    fill = loaders::diskFill,
                    recentlyDone = loaders::recentlyDone,
                    fetch = loaders::fetchToDisk,
                )
                Log.i(TAG, "Library art: $fetched of ${paths.size} files fetched or checked in ${System.currentTimeMillis() - started} ms; the cache is ${(loaders.diskFill() * 100).toInt()}% full")
            }
        }
    }

    private fun metered(): Boolean = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    companion object {
        private const val TAG = "LibraryArtFetch"
        /** After a sync, the opened shelf goes first: its own fetch ahead starts after 2 s. */
        const val AFTER_SYNC_MS = 5_000L
        /** The cache is left this much room: past it the fetch stops, so nothing the screens drew is pushed out. */
        const val FILL_LIMIT = 0.9
        const val BUSY_WAIT_MS = 500L
        /** Failures in a row after which the server is taken to be down and the pass stops. */
        const val FAILURES_IN_A_ROW = 10

        /**
         * Fetches [paths] in order with [fetch], one at a time: waiting while [held], leaving out what
         * was [recentlyDone], and stopping when the cache's [fill] reaches [FILL_LIMIT] or after
         * [FAILURES_IN_A_ROW] failed fetches in a row. Returns how many were fetched or checked.
         */
        suspend fun fetchAll(
            paths: List<String>,
            held: () -> Boolean,
            fill: () -> Double,
            recentlyDone: (String) -> Boolean,
            fetch: suspend (String) -> Boolean,
        ): Int {
            var count = 0
            var failures = 0
            for (path in paths) {
                coroutineContext.ensureActive()
                while (held()) delay(BUSY_WAIT_MS)
                if (fill() >= FILL_LIMIT) break
                if (recentlyDone(path)) continue
                if (fetch(path)) {
                    count++
                    failures = 0
                } else if (++failures >= FAILURES_IN_A_ROW) {
                    break
                }
            }
            return count
        }
    }
}
