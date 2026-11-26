package app.genkan.core

/**
 * One step of a core's ladder: the core options that set it, over the core's own (see
 * [TuningLadder.assignment]). A [risky] step may break how a game looks or runs, so it is never
 * taken on its own: only from a catalog profile measured on this device. A [live] step the core
 * applies while the game runs; another waits for the next start.
 */
data class TuningStep(
    val id: String,
    val options: Map<String, String>,
    val risky: Boolean = false,
    val live: Boolean = true,
    /** What the step does, short enough for a button on the game's details (resolutions as the console's times). */
    val label: String = id,
)

/**
 * A core's steps, from the best-looking to the lightest, with the core's own [defaultId] among
 * them. [defaults] are the values every key the ladder touches has without a step, so going back
 * to a step sets them again.
 */
data class TuningLadder(val steps: List<TuningStep>, val defaultId: String, val defaults: Map<String, String>) {
    init {
        require(steps.any { it.id == defaultId }) { "No default step $defaultId" }
        require(steps.all { defaults.keys.containsAll(it.options.keys) }) { "A step sets a key with no default" }
    }

    fun step(id: String?): TuningStep? = steps.firstOrNull { it.id == id }

    val default: TuningStep get() = step(defaultId)!!

    /** Every key the ladder touches, as [id] sets it (the defaults for those it leaves). */
    fun assignment(id: String): Map<String, String> = defaults + (step(id)?.options ?: emptyMap())

    /**
     * The safe step nearest [id] on the way to the default, [id] itself when it's safe: what a
     * risky step falls back to where it wasn't measured.
     */
    fun safeToward(id: String): TuningStep {
        val from = steps.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return default
        val home = steps.indexOf(default)
        val path = if (from <= home) (from..home) else (from downTo home)
        return path.map { steps[it] }.first { !it.risky }
    }
}

/** A catalog entry's step for a core, and the device it was measured on (a risky step needs one). */
data class BundledTuning(val step: String, val measuredOn: String?)

/** Which step a game gets, and where it came from. */
data class ResolvedTuning(val step: TuningStep, val source: Source) {
    enum class Source { OVERRIDE, CATALOG, DEFAULT }
}

object TuningLadders {
    // Dolphin's libretro code re-reads these as the game runs (DolphinLibretro/Main.cpp's IsUpdated
    // checks). Its EFB scale changes then with Genkan's patch (patches/dolphin), measured on the
    // Thor both ways. VI skip acts only once a game falls behind, which no round has shown yet: its
    // steps wait for a restart.
    private val dolphin = TuningLadder(
        steps = listOf(
            TuningStep("efb-2x", mapOf("dolphin_efb_scale" to "2"), label = "2×"),
            TuningStep("default", emptyMap(), label = "1×"),
            TuningStep("vi-skip", mapOf("dolphin_vi_skip" to "enabled"), risky = true, live = false, label = "VI skip"),
            TuningStep("vi-skip-cpu-80", mapOf("dolphin_vi_skip" to "enabled", "dolphin_cpu_clock_rate" to "0.80"), risky = true, live = false, label = "VI skip, CPU 80%"),
        ),
        defaultId = "default",
        defaults = mapOf("dolphin_efb_scale" to "1", "dolphin_vi_skip" to "disabled", "dolphin_cpu_clock_rate" to "1.00"),
    )

    // PPSSPP's core aborts when its resolution changes as the game runs (measured on the Thor):
    // its steps wait for a restart.
    private val ppsspp = TuningLadder(
        steps = listOf(
            TuningStep("1920x1088", mapOf("ppsspp_internal_resolution" to "1920x1088"), live = false, label = "4×"),
            TuningStep("default", emptyMap(), live = false, label = "3×"),
            TuningStep("960x544", mapOf("ppsspp_internal_resolution" to "960x544"), live = false, label = "2×"),
            TuningStep("480x272", mapOf("ppsspp_internal_resolution" to "480x272"), live = false, label = "1×"),
        ),
        defaultId = "default",
        defaults = mapOf("ppsspp_internal_resolution" to "1440x816"),
    )

    /** The ladder named [tuning] (a core's CoreDef.tuning); null for a core light enough to need none. */
    fun of(tuning: String): TuningLadder? = when (tuning) {
        "dolphin" -> dolphin
        "ppsspp" -> ppsspp
        else -> null
    }

    /**
     * The step for a game: the device's own choice for the game ([override], never a risky step),
     * then the catalog's for its release ([bundled]: a risky step only where it was measured on
     * this [device], else the safe step nearest it), then the core's default. A step the ladder
     * doesn't have counts as none.
     */
    fun resolve(ladder: TuningLadder, override: String?, bundled: BundledTuning?, device: String): ResolvedTuning {
        ladder.step(override)?.takeIf { !it.risky }?.let { return ResolvedTuning(it, ResolvedTuning.Source.OVERRIDE) }
        val fromCatalog = bundled?.let { ladder.step(it.step) }
        if (fromCatalog != null) {
            val measuredHere = !bundled.measuredOn.isNullOrBlank() && bundled.measuredOn == device
            val step = if (!fromCatalog.risky || measuredHere) fromCatalog else ladder.safeToward(fromCatalog.id)
            return ResolvedTuning(step, ResolvedTuning.Source.CATALOG)
        }
        return ResolvedTuning(ladder.default, ResolvedTuning.Source.DEFAULT)
    }
}
