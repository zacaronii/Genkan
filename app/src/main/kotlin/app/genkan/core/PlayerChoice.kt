package app.genkan.core

import app.genkan.launch.EmulatorCatalog

/**
 * The ways a console's games can be played, for Settings to choose from: each built-in core this
 * build has for it, then its emulator app (CoreCatalog.EXTERNAL) when Genkan knows one.
 */
object PlayerChoice {

    data class Option(val value: String, val label: String)

    /** The consoles Settings offers a choice for, by platform, in the order it lists them, with their names. */
    val CONSOLES: List<Pair<String, String>> = listOf(
        "ps2" to "PlayStation 2",
    )

    /** [platform]'s options, its first core first: the default. [debug]: whether debug-only cores count. */
    fun options(platform: String, debug: Boolean = app.genkan.BuildConfig.DEBUG): List<Option> {
        val cores = CoreCatalog.coresFor(platform, debug).map { Option(it.id, "${it.displayName} (built in)") }
        val app = EmulatorCatalog.candidatesFor(platform).firstOrNull()?.let { Option(CoreCatalog.EXTERNAL, it.displayName) }
        return cores + listOfNotNull(app)
    }

    /** The consoles with more than one option in this build, for Settings' rows. */
    fun consoles(debug: Boolean = app.genkan.BuildConfig.DEBUG): List<Pair<String, String>> =
        CONSOLES.filter { (platform, _) -> options(platform, debug).size > 1 }

    /** What plays [platform] now, given the kept [choice]: the chosen option when it's one of them, else the first. */
    fun current(platform: String, choice: Map<String, String>, debug: Boolean = app.genkan.BuildConfig.DEBUG): Option? {
        val all = options(platform, debug)
        return all.firstOrNull { it.value == choice[platform] } ?: all.firstOrNull()
    }

    /** The option after [platform]'s current one, round to the first: what a press on its row chooses. */
    fun next(platform: String, choice: Map<String, String>, debug: Boolean = app.genkan.BuildConfig.DEBUG): Option? {
        val all = options(platform, debug)
        val at = all.indexOf(current(platform, choice, debug))
        return all.getOrNull((at + 1) % all.size.coerceAtLeast(1))
    }
}
