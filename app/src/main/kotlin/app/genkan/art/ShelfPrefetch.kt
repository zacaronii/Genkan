package app.genkan.art

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import app.genkan.launch.LocalShelves
import app.genkan.library.GameEntity
import app.genkan.library.hasBoxFaces
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Fetches the art of the opened shelf's games ahead of the focus, nearest first, a few at a time
 * on [ArtLoaders.background], so moving along the shelf doesn't wait on the network; and, on the
 * way, asks once a day whether each file changed on the server ([ArtCacheStrategy.REVALIDATE]).
 * It starts after the shelf's own art has had its moment ([SETTLE_MS]), stops when the shelf
 * changes or is left, and does nothing on a metered connection.
 */
@Singleton
class ShelfPrefetch @Inject constructor(
    @ApplicationContext private val context: Context,
    private val loaders: ArtLoaders,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _busy = MutableStateFlow(false)
    /** Whether it's fetching the opened shelf now: the whole library's fetch waits for it. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Fetches [games]' art from [index] outwards; [fullBox] adds the box's front and back. */
    fun start(games: List<GameEntity>, index: Int, fullBox: Boolean) {
        job?.cancel()
        _busy.value = true
        lateinit var mine: Job
        mine = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(SETTLE_MS)
                // Signed out (or the session not loaded yet): there is no server to ask.
                if (metered() || !loaders.signedIn()) return@launch
                // On disk and checked lately: nothing to do for it (a sync starts the shelf over).
                val paths = pathsFrom(games, index, fullBox).filterNot { loaders.recentlyDone(it) }
                val queue = Channel<String>(Channel.UNLIMITED).apply { paths.forEach(::trySend); close() }
                Log.i(TAG, "Fetching ahead: ${paths.size} files")
                // The workers within, so the shelf is busy until the last file is done.
                coroutineScope {
                    repeat(WORKERS) {
                        launch {
                            for (path in queue) {
                                ensureActive()
                                loaders.fetchToDisk(path)
                            }
                        }
                    }
                }
            } finally {
                // Only the latest start says the shelf is done: a cancelled one was replaced.
                if (job === mine) _busy.value = false
            }
        }
        job = mine
        mine.start()
    }

    /** Stops fetching: the shelf was left. */
    fun stop() {
        job?.cancel()
        job = null
        _busy.value = false
    }

    private fun metered(): Boolean = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    companion object {
        private const val TAG = "ShelfPrefetch"
        /** How long after the shelf opens or the focus rests before fetching ahead starts. */
        const val SETTLE_MS = 2_000L
        private const val WORKERS = 2

        /**
         * What the shelf draws for each of [games], nearest [index] first: the cover (the large one,
         * else the small), the spine, the logo, the disc or cartridge, and with the [fullBox] the box's
         * front and back. Local games have no RomM art.
         */
        fun pathsFrom(games: List<GameEntity>, index: Int, fullBox: Boolean): List<String> =
            games.withIndex()
                .filterNot { LocalShelves.isLocal(it.value) }
                .sortedBy { abs(it.index - index) }
                .flatMap { (_, game) ->
                    val box = fullBox && game.hasBoxFaces()
                    listOfNotNull(
                        game.coverLargePath ?: game.coverPath,
                        game.box2dSidePath,
                        game.logoPath,
                        game.physicalPath,
                        game.box2dPath.takeIf { box },
                        game.box2dBackPath.takeIf { box },
                    )
                }
                .distinct()
    }
}
