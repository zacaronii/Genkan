package app.genkan.stats

import app.genkan.home.LauncherPresence
import app.genkan.reader.Lines
import app.genkan.reader.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reading time and characters for what's playing, by Furoshiki's rules ([ActivityTracker]):
 * only kana and kanji count, a line shown again isn't counted again, active time needs a line
 * or a lookup within the last five minutes, and days start at 04:01. Runs on one thread.
 */
@Singleton
class ReadingStats @Inject constructor(
    private val dao: ReadingDao,
    private val nowPlaying: NowPlaying,
    private val launcher: LauncherPresence,
) {
    private val thread = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + thread)
    private var tracker: ActivityTracker? = null
    private var trackedGame: String? = null
    private var started = false

    private val _session = MutableStateFlow<ReadingCounters?>(null)
    /** The current session's counters, for the reader's status line. */
    val session: StateFlow<ReadingCounters?> = _session.asStateFlow()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            var ticks = 0
            while (true) {
                delay(HEARTBEAT_MS)
                val now = System.currentTimeMillis()
                // The tracker credits time only within its idle timeout of the last line or
                // lookup, so a game left running unread stops counting by itself; and none at
                // all while Genkan's own launcher has the top screen: the game isn't being read.
                tracker?.onHeartbeat(now, reading = !launcher.inFront.value)
                if (++ticks % FLUSH_EVERY == 0) tracker?.flush()
                publish()
            }
        }
    }

    /** A line reached the backlog. [counted] is the part that is new reading (see takesBack). */
    fun onLine(text: String, counted: String) {
        val now = System.currentTimeMillis()
        scope.launch {
            val t = trackerFor(nowPlaying.game.value) ?: return@launch
            t.onLine(TextLine(text = counted, sourceId = Lines.sourceId(text)), now)
            publish()
        }
    }

    /** A lookup: the reader is still there. */
    fun onInteraction() {
        val now = System.currentTimeMillis()
        scope.launch { tracker?.onInteraction(now) }
    }

    /** Writes what's unsaved (the app may be killed in the background). */
    fun flush() {
        scope.launch { tracker?.flush() }
    }

    private fun trackerFor(game: NowPlaying.Game): ActivityTracker? {
        if (game.key == NowPlaying.NONE.key) return null
        if (trackedGame != game.key) {
            tracker?.flush()
            tracker = ActivityTracker(RoomActivityStore(dao, game.title, game.platform, game.rommRomId), game.key)
            trackedGame = game.key
        }
        return tracker
    }

    private fun publish() {
        _session.value = tracker?.session
    }

    private companion object {
        const val HEARTBEAT_MS = 5_000L
        /** Flush every 30 s. */
        const val FLUSH_EVERY = 6
    }
}
