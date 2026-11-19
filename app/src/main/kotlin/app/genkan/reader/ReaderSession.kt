package app.genkan.reader

import app.genkan.stats.ReadingStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** A hook thread seen in this game, with its latest text so the picker can show what it is. */
data class ThreadSample(val thread: String, val hookCode: String, val lines: Int, val latest: String, val characters: Int = 0) {
    /** Dialogue threads carry whole lines; junk ones (per-character text measuring) carry one. */
    val charactersPerLine: Double get() = if (lines == 0) 0.0 else characters.toDouble() / lines
}

/**
 * Texthooker messages made into the current game's backlog. Each thread has its own
 * [LineFeed]; when the source names threads, only the game's chosen dialogue thread reaches
 * the backlog, and the others are offered in the picker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ReaderSession @Inject constructor(
    private val client: TextHookClient,
    private val nowPlaying: NowPlaying,
    private val dao: ReaderDao,
    private val stats: ReadingStats,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val feeds = HashMap<String, LineFeed>()
    private val settleJobs = HashMap<String, Job>()
    private var started = false

    val status: StateFlow<HookStatus> get() = client.status
    val game: StateFlow<NowPlaying.Game> get() = nowPlaying.game

    val lines: StateFlow<List<ReaderLineEntity>> = nowPlaying.game
        .flatMapLatest { dao.recent(it.key, BACKLOG) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val chosenThread: StateFlow<HookThreadEntity?> = nowPlaying.game
        .flatMapLatest { dao.chosenThread(it.key) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _threads = MutableStateFlow<List<ThreadSample>>(emptyList())
    /** Threads seen since the current game started, the likeliest dialogue (longest lines) first. */
    val threads: StateFlow<List<ThreadSample>> = _threads.asStateFlow()

    fun start() {
        if (started) return
        started = true
        client.start(scope)
        stats.start()
        scope.launch { client.messages.collect { lock.withLock { onMessage(it) } } }
        scope.launch {
            var previous = nowPlaying.game.value.key
            nowPlaying.game.collect { game ->
                if (game.key != previous) lock.withLock { resetThreads() }
                previous = game.key
            }
        }
    }

    /** A line from another source (OCR): it goes through the same cleaning as hooked text. */
    fun submit(message: HookMessage) {
        scope.launch { lock.withLock { onMessage(message) } }
    }

    suspend fun choose(sample: ThreadSample) = dao.chooseThread(HookThreadEntity(nowPlaying.game.value.key, sample.thread, sample.hookCode))

    suspend fun forgetChoice() = dao.forgetThread(nowPlaying.game.value.key)

    private suspend fun onMessage(message: HookMessage) {
        if (message.thread.isNotEmpty()) {
            noteThread(message)
            // Until a dialogue thread is chosen the picker shows them all and nothing is read.
            if (chosenThread.value?.thread != message.thread) return
        }
        val now = System.currentTimeMillis()
        val feed = feeds.getOrPut(message.thread) { LineFeed() }
        feed.take(message.text, now).forEach { handOver(it) }
        if (feed.hasPending) {
            settleJobs[message.thread]?.cancel()
            settleJobs[message.thread] = scope.launch {
                delay(LineFeed.SETTLE_MS)
                lock.withLock { feed.settle(System.currentTimeMillis())?.let { handOver(it) } }
            }
        }
    }

    private suspend fun handOver(out: LineFeed.Out) {
        val key = nowPlaying.game.value.key
        val now = System.currentTimeMillis()
        val last = out.takesBack?.let { dao.last(key) }
        if (last != null && last.text == out.takesBack) {
            dao.replace(last.id, out.text, now)
        } else {
            dao.insert(ReaderLineEntity(gameKey = key, text = out.text, at = now))
        }
        // A line that carries on one already counted adds only its new part to the stats.
        val takenBack = out.takesBack
        val counted = if (takenBack != null && out.text.startsWith(takenBack)) out.text.removePrefix(takenBack) else out.text
        stats.onLine(out.text, counted)
    }

    private fun noteThread(message: HookMessage) {
        val text = Lines.normalise(message.text)
        val samples = _threads.value.toMutableList()
        val i = samples.indexOfFirst { it.thread == message.thread }
        if (i < 0) {
            samples += ThreadSample(message.thread, message.hookCode, 1, text, text.length)
        } else {
            val s = samples[i]
            samples[i] = s.copy(
                lines = s.lines + 1,
                latest = text.ifBlank { s.latest },
                hookCode = message.hookCode.ifEmpty { s.hookCode },
                characters = s.characters + text.length,
            )
        }
        _threads.value = samples.sortedByDescending { it.charactersPerLine }
    }

    private fun resetThreads() {
        settleJobs.values.forEach { it.cancel() }
        settleJobs.clear()
        feeds.clear()
        _threads.value = emptyList()
    }

    private companion object {
        const val BACKLOG = 300
    }
}
