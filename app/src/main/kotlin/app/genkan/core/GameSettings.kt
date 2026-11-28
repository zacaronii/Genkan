package app.genkan.core

/** Where a game's setting gets its value, as its Game settings sheet says it. */
enum class SettingSource(val label: String) {
    DEFAULT("default"),
    CATALOG("catalog"),
    LEARNER("learned"),
    CONSOLE("console"),
    GAME("this game"),
}

/**
 * One row of a game's settings: [key] is the core option's, or [GRAPHICS] for the renderer
 * (GameGraphics); [value] what it is now, said as Genkan says it, and [source] why.
 */
data class SettingRow(val key: String, val label: String, val value: String, val source: SettingSource)

/** A game's settings in the built-in player, each with where its value comes from. */
object GameSettings {
    /** The key of the renderer's row, which GameGraphics keeps rather than OptionPins. */
    const val GRAPHICS = "graphics"

    /**
     * [def]'s rows for a game that starts at [resolved] on [ladder] (null: the core has none), with
     * its console's and its own pins: the renderer where the ladder has a choice of them, then each of
     * [options] (the offered ones). A pin wins (the game's over the console's), then the step, when
     * it sets the key and came from learning or the catalog, then the core's own value.
     */
    fun rows(
        def: CoreDef,
        ladder: TuningLadder?,
        resolved: ResolvedTuning?,
        options: List<CoreOption>,
        console: Map<String, String>,
        game: Map<String, String>,
    ): List<SettingRow> = buildList {
        if (ladder != null && ladder.renderers.isNotEmpty() && resolved != null) {
            add(SettingRow(GRAPHICS, "Graphics", resolved.step.label, stepSource(resolved.source)))
        }
        for (option in options) {
            val stepped = resolved?.takeIf { it.source != ResolvedTuning.Source.DEFAULT && option.key in it.step.options }
            val (value, source) = when {
                option.key in game -> game.getValue(option.key) to SettingSource.GAME
                option.key in console -> console.getValue(option.key) to SettingSource.CONSOLE
                stepped != null -> stepped.step.options.getValue(option.key) to stepSource(stepped.source)
                else -> (def.variables[option.key] ?: ladder?.defaults?.get(option.key) ?: option.default) to SettingSource.DEFAULT
            }
            add(SettingRow(option.key, option.label, option.labelOf(value), source))
        }
    }

    private fun stepSource(source: ResolvedTuning.Source): SettingSource = when (source) {
        ResolvedTuning.Source.CHOSEN -> SettingSource.GAME
        ResolvedTuning.Source.OVERRIDE -> SettingSource.LEARNER
        ResolvedTuning.Source.CATALOG -> SettingSource.CATALOG
        ResolvedTuning.Source.DEFAULT -> SettingSource.DEFAULT
    }
}
