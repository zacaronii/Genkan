package app.genkan.armsx2

/**
 * A script's frames for a game in ARMSX2, which tells Genkan nothing of its own frames: they're
 * counted from the time the game has been running (60 a second), not counting pauses. PINE has no
 * events either: a pause that ends is taken as a possible savestate load (ARMSX2 pauses for its
 * menu, where states are loaded), so the script hears of one before the next frame.
 */
class PineTicks(startMs: Long) {

    enum class Step { FRAME, STATE_LOADED, WAIT, STOP }

    private var lastMs = startMs
    private var runningMs = 0L
    private var paused = false

    /** Frames the game has run, at the last [step]. */
    val frame: Long get() = runningMs * FRAMES_PER_SECOND / 1000

    /**
     * What [status] (null: ARMSX2 didn't answer) at [nowMs] means: run a frame; tell the script a
     * state may have loaded, then run one; wait while paused; or stop (no game, or no ARMSX2).
     */
    fun step(status: PineStatus?, nowMs: Long): Step {
        val since = (nowMs - lastMs).coerceAtLeast(0)
        lastMs = nowMs
        return when (status) {
            null, PineStatus.SHUTDOWN -> Step.STOP
            PineStatus.PAUSED -> {
                paused = true
                Step.WAIT
            }
            PineStatus.RUNNING -> {
                if (paused) {
                    // The time paused isn't the game's.
                    paused = false
                    Step.STATE_LOADED
                } else {
                    runningMs += since
                    Step.FRAME
                }
            }
        }
    }

    private companion object {
        const val FRAMES_PER_SECOND = 60L
    }
}
