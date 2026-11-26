package app.genkan.core

/**
 * How fast the built-in player's game runs, a second at a time: the core's frames against its
 * own rate, and how long the core took over them (the wait for the next frame left out), with
 * the reader's own work between frames beside it. A threaded core's frame waits on its workers,
 * so for how much room is left, the busiest thread and the GPU's load count, not the frame's time. [behind] says the game has been slow for the
 * last [WINDOW] seconds, for whatever should give way to it.
 */
class PlayerStats {

    /** One period's numbers, as [com.swordfish.libretrodroid.GenkanTap.frameStats] and the player give them. */
    data class Sample(
        val runs: Long,
        val runNanos: Long,
        val longestNanos: Long,
        val p95Nanos: Long,
        val steps: Long,
        val contentFpsMilli: Long,
        val elapsedNanos: Long,
        /** The reader's own work between frames: the script, and the frame ring's shots. */
        val scriptNanos: Long = 0,
        val ringNanos: Long = 0,
        /** The player's busiest thread over the period (see [ThreadLoad]), and the GPU's load and clock; null where unknown. */
        val busiest: ThreadLoad.Busy? = null,
        val gpuBusyPercent: Int? = null,
        val gpuMhz: Int? = null,
        /** Steps whose run was held back, the core's sound being ahead (libretrodroid's audiopace.h): on time. */
        val paced: Long = 0,
        /** The sound the core made (stereo frames), and its own rate in thousandths of a frame a second. */
        val audioFrames: Long = 0,
        val sampleRateMilli: Long = 0,
        /** Reads of the sound buffer that found it short and played silence for the rest. */
        val dryReads: Long = 0,
    )

    /** One second of play, as logged. */
    data class Row(
        /**
         * Frames the core ran a second, and the game's speed against its own rate (1.0: full speed): its
         * runs and the steps held back for being ahead.
         */
        val fps: Double,
        val speed: Double,
        val shownFps: Double,
        val averageMs: Double,
        val p95Ms: Double,
        val longestMs: Double,
        val scriptMs: Double,
        val ringMs: Double,
        val busiest: ThreadLoad.Busy?,
        val gpuBusyPercent: Int?,
        val gpuMhz: Int?,
        /** Steps held back a second. */
        val paced: Double = 0.0,
        /**
         * The sound the core made against its own rate (1.0: in time), the other side of speed: a core
         * whose emulation runs on a thread of its own plays fast with its runs on time; null with no sound.
         */
        val sound: Double? = null,
        /** The sound buffer's dry reads in the period: each a gap of silence. */
        val dry: Long = 0,
    ) {
        fun line(): String = "fps %.1f speed %.0f%% shown %.1f core avg %.2f p95 %.2f max %.2f ms, script %.2f, ring %.2f ms/s".format(
            fps, speed * 100, shownFps, averageMs, p95Ms, longestMs, scriptMs, ringMs,
        ) + " | sound " + (sound?.let { "%.1f%%".format(it * 100) } ?: "?") + ", paced %.0f, dry %d".format(paced, dry) + " | busiest " + (busiest?.let { "%s#%d %.0f%%".format(it.name, it.tid, it.share * 100) } ?: "?") +
            " | gpu " + (gpuBusyPercent?.let { "$it%" } ?: "?") + " @ " + (gpuMhz?.let { "$it MHz" } ?: "?")
    }

    private val speeds = ArrayDeque<Double>()

    /** The game has been under [SLOW] of its speed for each of the last [WINDOW] seconds. */
    var behind = false
        private set

    /** [sample] as a row; null when the period was empty (paused, or no game). */
    fun add(sample: Sample): Row? {
        if (sample.elapsedNanos <= 0 || sample.steps == 0L) {
            speeds.clear()
            behind = false
            return null
        }
        val seconds = sample.elapsedNanos / 1e9
        val fps = sample.runs / seconds
        val rate = sample.contentFpsMilli / 1000.0
        val speed = if (rate > 0) (sample.runs + sample.paced) / seconds / rate else 0.0
        val soundRate = sample.sampleRateMilli / 1000.0
        speeds.addLast(speed)
        while (speeds.size > WINDOW) speeds.removeFirst()
        behind = speeds.size == WINDOW && speeds.all { it < SLOW }
        return Row(
            fps = fps,
            speed = speed,
            shownFps = sample.steps / seconds,
            averageMs = if (sample.runs > 0) sample.runNanos / sample.runs / 1e6 else 0.0,
            p95Ms = sample.p95Nanos / 1e6,
            longestMs = sample.longestNanos / 1e6,
            scriptMs = sample.scriptNanos / 1e6 / seconds,
            ringMs = sample.ringNanos / 1e6 / seconds,
            busiest = sample.busiest,
            gpuBusyPercent = sample.gpuBusyPercent,
            gpuMhz = sample.gpuMhz,
            paced = sample.paced / seconds,
            sound = if (soundRate > 0 && sample.audioFrames > 0) sample.audioFrames / seconds / soundRate else null,
            dry = sample.dryReads,
        )
    }

    companion object {
        const val WINDOW = 2
        /** Under this share of its own rate, a game is slow (59.94 of 60 is not). */
        const val SLOW = 0.97
    }
}
