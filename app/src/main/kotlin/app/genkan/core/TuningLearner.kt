package app.genkan.core

/**
 * Learns a game's ladder step as it's played, to hold its target: full speed at the core's rate.
 * Only steps that apply as the game runs and aren't risky are tried. Fed one [Second] at a time:
 *
 * - The game falls behind (a [WINDOW] under [SLOW]): the next lighter step is tried for a window,
 *   kept when speed rose by [GAIN] or more, else the game goes back.
 * - The game has room (a [ROOM] at full speed on average, no second behind, its busiest thread
 *   and the GPU under [LOAD] on average; a second's speed jitters around full):
 *   the next better-looking step is tried for a window, kept while full speed holds, else the game
 *   goes back and that step isn't tried again this session.
 *
 * Nothing is learned in the first [SETTLE] seconds (loading), while the device is hotter than
 * [HOT_C], or across a pause ([paused] starts the windows again). A kept step is a [Decision] to
 * remember for the game; every try and return is one to apply now.
 */
class TuningLearner(private val ladder: TuningLadder, start: String) {

    /** One second of play: speed against the core's rate (1.0: full), the busiest thread's share of a CPU, the GPU's busy %, the CPU's heat. */
    data class Second(val speed: Double, val busiest: Double?, val gpuBusyPercent: Int?, val hottestC: Int?)

    /** Move the game to [step] now; [keep]: remember it for the game, with [why]. */
    data class Decision(val step: String, val keep: Boolean, val why: String)

    var step: String = ladder.step(start)?.id ?: ladder.defaultId
        private set

    private enum class Phase { WATCH, TRY_LIGHTER, TRY_BETTER }

    private var phase = Phase.WATCH
    private var played = 0
    private val recent = ArrayDeque<Second>()
    private var from = step
    private var before = 0.0
    private var trial = 0
    private val failedBetter = mutableSetOf<String>()
    private val failedLighter = mutableSetOf<String>()

    /** The game was paused or left: windows start again from its next second. */
    fun paused() {
        recent.clear()
    }

    fun second(s: Second): Decision? {
        played++
        if (played <= SETTLE) return null
        if ((s.hottestC ?: 0) > HOT_C) {
            // Too hot to judge a step by its speed: a try under way goes back.
            recent.clear()
            return if (phase != Phase.WATCH) back("too hot to try") else null
        }
        recent.addLast(s)
        while (recent.size > ROOM) recent.removeFirst()
        return when (phase) {
            Phase.WATCH -> watch()
            Phase.TRY_LIGHTER, Phase.TRY_BETTER -> judge(s)
        }
    }

    private fun watch(): Decision? {
        val window = recent.takeLast(WINDOW)
        if (window.size == WINDOW && window.average() < SLOW) {
            val lighter = neighbour(+1)?.takeIf { it.id !in failedLighter } ?: return null
            return start(Phase.TRY_LIGHTER, lighter, window.average(), "trying ${lighter.id}: the game was behind")
        }
        if (recent.size == ROOM && hasRoom(recent)) {
            val better = neighbour(-1)?.takeIf { it.id !in failedBetter } ?: return null
            return start(Phase.TRY_BETTER, better, recent.average(), "trying ${better.id}: the game had room")
        }
        return null
    }

    private fun judge(s: Second): Decision? {
        trial++
        if (trial <= TRIAL_SETTLE) {
            // The step's first seconds (the core rebuilding its buffers) don't count.
            recent.clear()
            return null
        }
        val window = recent.takeLast(WINDOW)
        // A real drop ends a better-looking try at once; a hitch is left to the window's average.
        if (phase == Phase.TRY_BETTER && s.speed < DROP) return fail("${step} slowed the game")
        if (window.size < WINDOW) return null
        val speed = window.average()
        return when (phase) {
            Phase.TRY_LIGHTER -> if (speed >= before + GAIN) keep("faster at ${step}") else fail("${step} didn't help")
            Phase.TRY_BETTER -> if (speed >= FULL) keep("full speed at ${step}") else fail("${step} slowed the game")
            Phase.WATCH -> null
        }
    }

    private fun start(next: Phase, to: TuningStep, speed: Double, why: String): Decision {
        phase = next
        from = step
        before = speed
        trial = 0
        step = to.id
        recent.clear()
        return Decision(to.id, keep = false, why = why)
    }

    private fun keep(why: String): Decision {
        phase = Phase.WATCH
        recent.clear()
        return Decision(step, keep = true, why = why)
    }

    private fun fail(why: String): Decision {
        if (phase == Phase.TRY_BETTER) failedBetter += step else failedLighter += step
        return back(why)
    }

    private fun back(why: String): Decision {
        phase = Phase.WATCH
        step = from
        recent.clear()
        return Decision(from, keep = false, why = "back to $from: $why")
    }

    /** The live, safe step [direction] places along the ladder (+1 lighter, -1 better-looking), or null. */
    private fun neighbour(direction: Int): TuningStep? {
        val at = ladder.steps.indexOfFirst { it.id == step }
        val next = ladder.steps.getOrNull(at + direction) ?: return null
        val current = ladder.steps[at]
        return next.takeIf { it.live && !it.risky && current.live }
    }

    private fun hasRoom(seconds: Collection<Second>): Boolean =
        seconds.sumOf { it.speed } / seconds.size >= FULL &&
            seconds.all { it.speed >= SLOW } &&
            seconds.sumOf { it.busiest ?: 1.0 } / seconds.size < LOAD &&
            seconds.sumOf { (it.gpuBusyPercent ?: 100) / 100.0 } / seconds.size < LOAD

    private fun List<Second>.average(): Double = sumOf { it.speed } / size

    companion object {
        /** Seconds after the start that don't count: loading. */
        const val SETTLE = 30
        /** Seconds a step is judged over. */
        const val WINDOW = 20
        /** Seconds of a new step that don't count. */
        const val TRIAL_SETTLE = 3
        /** Seconds of room before a better-looking step is tried. */
        const val ROOM = 180
        /** Under this speed the game is behind. */
        const val SLOW = 0.97
        /** At or over this speed the game is at full speed. */
        const val FULL = 0.99
        /** A second this slow ends a better-looking try at once. */
        const val DROP = 0.90
        /** A lighter step is kept only for this much more speed. */
        const val GAIN = 0.03
        /** The busiest thread's share and the GPU's busy fraction under which the game has room. */
        const val LOAD = 0.60
        /** Over this, nothing is tried. */
        const val HOT_C = 75
    }
}
