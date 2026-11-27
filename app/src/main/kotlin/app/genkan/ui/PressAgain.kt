package app.genkan.ui

/**
 * A press that takes effect only when pressed again soon after (Settings' Clear rows): the first
 * press arms it, a second on the same thing within [windowMs] confirms it.
 */
class PressAgain(private val windowMs: Long = WINDOW_MS) {
    private var armed: Any? = null
    private var armedAtMs = 0L

    /** What's armed at [nowMs], or null. */
    fun armed(nowMs: Long): Any? = armed?.takeIf { nowMs - armedAtMs < windowMs }

    /** A press on [what] at [nowMs]: true when it confirms one armed before, else it arms it. */
    fun press(what: Any, nowMs: Long): Boolean {
        if (armed(nowMs) == what) {
            armed = null
            return true
        }
        armed = what
        armedAtMs = nowMs
        return false
    }

    companion object {
        const val WINDOW_MS = 4_000L
    }
}
