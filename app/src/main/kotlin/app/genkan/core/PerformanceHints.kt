package app.genkan.core

import android.content.Context
import android.os.PerformanceHintManager
import android.util.Log

/**
 * Tells the system's performance hints (ADPF) how long each frame's work takes against the frame
 * it has, so it can raise the clocks of the threads doing it before the game falls behind: the
 * emulation (GL) thread and the core's own busy workers ([HintThreads]). Without hint support it
 * does nothing.
 */
class PerformanceHints(context: Context, private val targetNanos: Long) {
    private val manager = context.getSystemService(PerformanceHintManager::class.java)
    @Volatile private var session: PerformanceHintManager.Session? = null
    private var threads: Set<Int> = emptySet()

    /** The threads the hints are for now; a new session when they changed (Android 13 can't change a session's). */
    @Synchronized
    fun use(tids: Set<Int>) {
        if (manager == null || tids.isEmpty() || tids == threads) return
        val next = runCatching { manager.createHintSession(tids.toIntArray(), targetNanos) }
            .onFailure { Log.w(TAG, "No hint session: $it") }.getOrNull()
        session?.close()
        session = next
        threads = if (next != null) tids else emptySet()
        if (next != null) Log.i(TAG, "Hint session for threads $tids, ${targetNanos / 1000} µs a frame")
    }

    /** One frame's work took [nanos]; on the emulation thread. */
    fun report(nanos: Long) {
        if (nanos > 0) session?.reportActualWorkDuration(nanos)
    }

    @Synchronized
    fun close() {
        session?.close()
        session = null
        threads = emptySet()
    }

    private companion object {
        const val TAG = "PerformanceHints"
    }
}

/** Which threads a performance hint session is for. */
object HintThreads {
    /** A worker busier than this (of one CPU) is part of the frame's work. */
    const val BUSY = 0.15
    /** At most this many workers beside the emulation thread. */
    const val MAX_WORKERS = 4

    /**
     * The emulation thread [gl] and the busiest workers in [busy], or null to keep [current]: a
     * worker counts once busy, and stays while it's in [busy] at all, so the session isn't made
     * again every second as loads move.
     */
    fun choose(gl: Int?, busy: List<ThreadLoad.Busy>, current: Set<Int>): Set<Int>? {
        gl ?: return null
        val alive = busy.map { it.tid }.toSet()
        val workers = current.filter { it != gl && it in alive }.toMutableSet()
        busy.filter { it.share >= BUSY && it.tid != gl }.forEach { if (workers.size < MAX_WORKERS) workers += it.tid }
        val next = setOf(gl) + workers
        return next.takeIf { it != current }
    }
}
