package app.genkan.art

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
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
 * waits while the opened shelf's own fetch ahead runs ([ShelfPrefetch.busy]), skips what was done
 * within the day, stops when the cache is [FILL_LIMIT] full, and does nothing on a metered
 * connection or signed out. A new sync starts it over.
 */
@Singleton
class LibraryArtFetch @Inject constructor(
    @ApplicationContext private val context: Context,
    private val loaders: ArtLoaders,
    private val sync: LibrarySync,
    private val dao: LibraryDao,
    private val shelf: ShelfPrefetch,
    private val viewSettings: LibraryViewSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            sync.status.filterIsInstance<SyncStatus.Done>().collectLatest {
                delay(AFTER_SYNC_MS)
                if (metered() || !loaders.signedIn()) return@collectLatest
                val paths = ShelfPrefetch.pathsFrom(dao.allGames(), 0, viewSettings.fullBox.first())
                val fetched = fetchAll(paths, shelfBusy = shelf::busy, fill = loaders::diskFill, recentlyDone = loaders::recentlyDone, fetch = loaders::fetchToDisk)
                Log.i(TAG, "Library art: $fetched of ${paths.size} files fetched or checked; the cache is ${(loaders.diskFill() * 100).toInt()}% full")
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

        /**
         * Fetches [paths] in order with [fetch], one at a time: waiting while [shelfBusy], leaving out
         * what was [recentlyDone], and stopping when the cache's [fill] reaches [FILL_LIMIT].
         * Returns how many were fetched or checked.
         */
        suspend fun fetchAll(
            paths: List<String>,
            shelfBusy: () -> Boolean,
            fill: () -> Double,
            recentlyDone: (String) -> Boolean,
            fetch: suspend (String) -> Boolean,
        ): Int {
            var count = 0
            for (path in paths) {
                coroutineContext.ensureActive()
                while (shelfBusy()) delay(BUSY_WAIT_MS)
                if (fill() >= FILL_LIMIT) break
                if (recentlyDone(path)) continue
                fetch(path)
                count++
            }
            return count
        }
    }
}
