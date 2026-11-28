package app.genkan.core

/** One value of a [CoreOption]: the core's own value, and what Genkan calls it. */
data class OptionValue(val value: String, val label: String)

/**
 * A core option the owner can set for a console or a game (OptionPins): [key] is the core's, [values]
 * the ones offered, [default] the one it has when nothing sets it. A [live] option applies as the game
 * runs; any other waits for the next start. Set, it's pinned: no ladder step changes it (the learner
 * leaves the steps touching it alone, TuningLadder.without). Offered only once [measured] on the
 * device: its speed and heat against the default, noted beside it.
 */
data class CoreOption(
    val core: String,
    val key: String,
    val label: String,
    val values: List<OptionValue>,
    val default: String,
    val live: Boolean = false,
    val measured: Boolean = false,
) {
    init {
        require(values.any { it.value == default }) { "$key's default isn't one of its values" }
    }

    fun labelOf(value: String?): String = values.firstOrNull { it.value == value }?.label ?: value.orEmpty()

    /** The value after [current]: unset (the default, or what tuning gives), then each value in turn, then unset again. */
    fun next(current: String?): String? {
        val order = listOf<String?>(null) + values.map { it.value }
        return order[(order.indexOf(current.takeIf { c -> values.any { it.value == c } }) + 1) % order.size]
    }
}

/** The options each core offers, by core id; none offered until measured on the device. */
object CoreOptions {
    private fun v(vararg pairs: Pair<String, String>) = pairs.map { (value, label) -> OptionValue(value, label) }

    // LRPS2 (libretro_core_options.h at the pinned commit). None is offered yet: each waits for its
    // round on the Thor (speed, sound and heat against the default, on one or two games).
    private val lrps2 = listOf(
        CoreOption("lrps2", "pcsx2_upscale_multiplier", "Resolution", v("1x (Native)" to "1×", "2x" to "2×", "4x" to "4×"), "1x (Native)"),
        CoreOption("lrps2", "pcsx2_ee_cycle_rate", "EE speed", v("75% (Underclock)" to "75%", "100% (Normal Speed)" to "100%", "130% (Overclock)" to "130%"), "100% (Normal Speed)"),
        CoreOption("lrps2", "pcsx2_ee_cycle_skip", "EE cycle skip", v("disabled" to "Off", "Mild Underclock" to "Mild", "Moderate Underclock" to "Moderate"), "disabled"),
        // On by default on arm64, where the native VU1 recompiler drives VU1.
        CoreOption("lrps2", "pcsx2_mtvu", "VU1 on its own thread", v("enabled" to "On", "disabled" to "Off"), "enabled"),
        CoreOption("lrps2", "pcsx2_instant_vu1", "Instant VU1", v("enabled" to "On", "disabled" to "Off"), "enabled"),
        CoreOption("lrps2", "pcsx2_blending_accuracy", "Blending", v("Minimum" to "Minimum", "Basic" to "Basic", "Medium" to "Medium", "High" to "High"), "Basic"),
        CoreOption("lrps2", "pcsx2_texture_filtering", "Texture filtering", v("Nearest" to "Nearest", "Bilinear (PS2)" to "Bilinear (PS2)", "Bilinear (Forced)" to "Bilinear"), "Bilinear (PS2)"),
        CoreOption("lrps2", "pcsx2_hw_download_mode", "Read-backs", v("Accurate" to "Accurate", "Unsynchronized" to "Unsynchronized"), "Accurate"),
        CoreOption("lrps2", "pcsx2_widescreen_hint", "Widescreen", v("disabled" to "Off", "enabled (16:9)" to "16:9"), "disabled"),
    )

    val all: List<CoreOption> = lrps2

    /** Every option [core] has, offered or not (a debug broadcast can pin any for a round). */
    fun of(core: String): List<CoreOption> = all.filter { it.core == core }

    /** The options [core] offers in Settings and a game's settings: the measured ones. */
    fun offered(core: String): List<CoreOption> = of(core).filter { it.measured }

    fun option(core: String, key: String): CoreOption? = of(core).firstOrNull { it.key == key }
}
