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
 * - The heat starts to cost something: [HOT_WINDOW] seconds hotter than [HOT_C] in which the fastest
 *   core's clock fell below [CLOCK_KEPT] of what it reached at the start of the session (throttling),
 *   or the game fell behind (under [SLOW] on average); or, whatever it costs, [CEILING_WINDOW] seconds
 *   at [CEILING_C] or more. The next lighter step is tried for a window, kept when the heat stops
 *   climbing, else the game goes back. A lighter step the core takes only at start is remembered
 *   for the game's next start instead ([Decision.nextStart]), once. Heat alone, with full speed and
 *   the clock held, changes nothing.
 *
 * Nothing else is learned in the first [SETTLE] seconds (loading), while the device is hotter than
 * [HOT_C] (no better-looking step then), or across a pause ([paused] starts the windows again). A
 * kept step is a [Decision] to remember for the game; every try and return is one to apply now.
 */
class TuningLearner(private val ladder: TuningLadder, start: String) {

    /**
     * One second of play: speed against the core's rate (1.0: full), the busiest thread's share of a
     * CPU, the GPU's busy %, the CPU's heat, and the fastest core's clock.
     */
    data class Second(val speed: Double, val busiest: Double?, val gpuBusyPercent: Int?, val hottestC: Int?, val fastestMhz: Int? = null)

    /**
     * Move the game to [step] now; [keep]: remember it for the game, with [why]. [nextStart]: don't
     * move now (the core takes the step only at start), only remember it.
     */
    data class Decision(val step: String, val keep: Boolean, val why: String, val nextStart: Boolean = false)

    var step: String = ladder.step(start)?.id ?: ladder.defaultId
        private set

    private enum class Phase { WATCH, TRY_LIGHTER, TRY_BETTER, TRY_COOLER }

    private var phase = Phase.WATCH
    private var played = 0
    private val recent = ArrayDeque<Second>()
    private var from = step
    private var before = 0.0
    private var trial = 0
    private val failedBetter = mutableSetOf<String>()
    private val failedLighter = mutableSetOf<String>()
    private var hotSeconds = 0
    /** The hot seconds in a row, up to a window of them. */
    private val hotRecent = ArrayDeque<Second>()
    private var ceilingSeconds = 0
    /** The fastest core's highest clock in the session's first [CLOCK_BASE] seconds of play. */
    private var clockBase = 0
    /** The heat a cooler try started at, and the hottest second since. */
    private var heatAtTry = 0
    private var heatSince = 0
    private val failedCooler = mutableSetOf<String>()
    private var nextStartNoted = false

    /** The game was paused or left: windows start again from its next second. */
    fun paused() {
        recent.clear()
    }

    fun second(s: Second): Decision? {
        played++
        if (played <= SETTLE) return null
        if (played <= SETTLE + CLOCK_BASE) clockBase = maxOf(clockBase, s.fastestMhz ?: 0)
        val hot = (s.hottestC ?: 0) > HOT_C
        hotSeconds = if (hot) hotSeconds + 1 else 0
        if (hot) hotRecent.addLast(s) else hotRecent.clear()
        while (hotRecent.size > HOT_WINDOW) hotRecent.removeFirst()
        ceilingSeconds = if ((s.hottestC ?: 0) >= CEILING_C) ceilingSeconds + 1 else 0
        if (phase == Phase.TRY_COOLER) return judgeCooler(s)
        if (hot) {
            // Too hot to judge a step by its speed: a try under way goes back. Kept hot, the
            // game steps down to cool.
            recent.clear()
            if (phase != Phase.WATCH) return back("too hot to try")
            return heatCost()?.let { cooler(s, it) }
        }
        recent.addLast(s)
        while (recent.size > ROOM) recent.removeFirst()
        return when (phase) {
            Phase.WATCH -> watch()
            Phase.TRY_LIGHTER, Phase.TRY_BETTER -> judge(s)
            Phase.TRY_COOLER -> null
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
            Phase.WATCH, Phase.TRY_COOLER -> null
        }
    }

    /** Why the heat calls for a lighter step now, or null while it costs nothing and stays under the ceiling. */
    private fun heatCost(): String? {
        if (ceilingSeconds >= CEILING_WINDOW) return "the device stayed at $CEILING_C °C"
        if (hotSeconds < HOT_WINDOW || hotRecent.size < HOT_WINDOW) return null
        val clock = hotRecent.maxOf { it.fastestMhz ?: 0 }
        if (clockBase > 0 && clock > 0 && clock < clockBase * CLOCK_KEPT) return "the device ran hot and its clock fell"
        if (hotRecent.sumOf { it.speed } / hotRecent.size < SLOW) return "the device ran hot and the game fell behind"
        return null
    }

    /** The next lighter safe step, to cool the device: tried now when it's live, else remembered for the next start. */
    private fun cooler(s: Second, why: String): Decision? {
        hotSeconds = 0
        hotRecent.clear()
        ceilingSeconds = 0
        val at = ladder.steps.indexOfFirst { it.id == step }
        val lighter = ladder.steps.getOrNull(at + 1)?.takeIf { !it.risky && it.id !in failedCooler } ?: return null
        if (lighter.live && ladder.steps[at].live) {
            phase = Phase.TRY_COOLER
            from = step
            trial = 0
            heatAtTry = s.hottestC ?: 0
            heatSince = 0
            step = lighter.id
            return Decision(lighter.id, keep = false, why = "trying ${lighter.id}: $why")
        }
        if (nextStartNoted) return null
        nextStartNoted = true
        return Decision(lighter.id, keep = true, why = "${lighter.id} from the next start: $why", nextStart = true)
    }

    /** A cooler try: kept once a window passes without the heat climbing past where it started. */
    private fun judgeCooler(s: Second): Decision? {
        trial++
        if (trial <= TRIAL_SETTLE) return null
        heatSince = maxOf(heatSince, s.hottestC ?: 0)
        if (trial < TRIAL_SETTLE + WINDOW) return null
        return if (heatSince <= heatAtTry) {
            phase = Phase.WATCH
            recent.clear()
            Decision(step, keep = true, why = "cooler at ${step}")
        } else {
            failedCooler += step
            back("${step} didn't cool the device")
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
        /** Over this, nothing better-looking is tried, and a try under way goes back. */
        const val HOT_C = 75
        /** Seconds over [HOT_C] the heat's cost is judged over. */
        const val HOT_WINDOW = 20
        /** Seconds of play after loading that set the clock the session counts as its own. */
        const val CLOCK_BASE = 60
        /** Under this share of that clock, the fastest core is throttled. */
        const val CLOCK_KEPT = 0.9
        /** At or over this for [CEILING_WINDOW] seconds, the game steps down whatever it costs. */
        const val CEILING_C = 92
        const val CEILING_WINDOW = 60
    }
}
