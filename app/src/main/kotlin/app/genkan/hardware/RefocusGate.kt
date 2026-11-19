package app.genkan.hardware

/**
 * Rate-limits FocusDirector launches. The Thor's vendor patch NPEs system_server when a window
 * is removed within ~400 ms of another being added (T5), so launches are spaced at least
 * [minIntervalMs] apart.
 */
class RefocusGate(private val minIntervalMs: Long = 400) {

    private var lastLaunchAt: Long? = null

    /** Milliseconds to wait before a launch requested at [now] may run. */
    fun delayFor(now: Long): Long {
        val last = lastLaunchAt ?: return 0
        return (last + minIntervalMs - now).coerceAtLeast(0)
    }

    fun markLaunched(now: Long) {
        lastLaunchAt = now
    }
}
