package app.genkan.ui

import app.genkan.input.PadKey

/**
 * A group of the settings page: its name in the list on the left, the status lines shown above
 * its rows, and the rows themselves. Plain data, so the grouping can change without touching the
 * layout, and a test can check that every [SetupAction] has a row.
 */
data class SettingsGroup(
    /** A short stable name, used for the screenshot of the group. */
    val id: String,
    val title: String,
    val info: List<SettingsInfo> = emptyList(),
    val rows: List<SettingsRow>,
)

/**
 * Lines with nothing to press, shown above a group's rows: [STATUS] is the status card (every
 * connection and state at a glance), the others one line each.
 */
enum class SettingsInfo { STATUS, ROMM_ACCOUNT, SHORTCUTS, VERSION, LICENCE }

/** The on/off settings a switch row can show. */
enum class SwitchKey { HOME, BUILT_IN_PLAYER, INSERT_ANIMATION, PSP_TEXT, AZAHAR_TEXT, ARMSX2_TEXT, FULL_BOX, PREFETCH_MANUALS, FACE_A_ON_RIGHT, LEARNING }

/** A row of a group, the only things the gamepad lands on. */
sealed interface SettingsRow {
    val title: String

    /** Every action this row can run, whatever state it is in. */
    val actions: Set<SetupAction>

    /** A row that does one thing when pressed: open a page, start a sync, ask for a grant. */
    data class Button(override val title: String, val action: SetupAction) : SettingsRow {
        override val actions get() = setOf(action)
    }

    /** A console's row, opening its page (ConsoleScreen); one per console the built-in player runs. */
    data class Console(override val title: String, val platform: String) : SettingsRow {
        override val actions get() = setOf(SetupAction.CONSOLE)
    }

    /**
     * An on/off row. Pressing it runs [turnOn] while [key] is off and [turnOff] while it is on;
     * most settings flip with one action, so [turnOff] defaults to the same one.
     */
    data class Switch(
        override val title: String,
        val key: SwitchKey,
        val turnOn: SetupAction,
        val turnOff: SetupAction = turnOn,
    ) : SettingsRow {
        override val actions get() = setOf(turnOn, turnOff)

        fun action(on: Boolean): SetupAction = if (on) turnOff else turnOn
    }
}

/**
 * The settings page's groups, in order: the status card, then what Genkan does (the library,
 * consoles, controls, reading, Anki), the servers in one place, the device, and About. A title is
 * four words at most; what a row does is one line under it (SetupScreen's explanations).
 */
object SettingsGroups {
    /** The actions a console's page runs (its text source), rather than a row here. */
    val ON_CONSOLE_PAGES: Set<SetupAction> = setOf(SetupAction.PSP_DEBUGGER, SetupAction.ARMSX2_PINE)

    val DEFAULT: List<SettingsGroup> = listOf(
        SettingsGroup(id = "status", title = "Status", info = listOf(SettingsInfo.STATUS), rows = emptyList()),
        SettingsGroup(
            id = "library",
            title = "Library",
            rows = listOf(
                SettingsRow.Switch("Full box", SwitchKey.FULL_BOX, SetupAction.FULL_BOX),
                SettingsRow.Switch("Fetch manuals", SwitchKey.PREFETCH_MANUALS, SetupAction.PREFETCH_MANUALS),
            ),
        ),
        SettingsGroup(
            id = "consoles",
            title = "Consoles",
            rows = listOf(
                SettingsRow.Switch("Built-in player", SwitchKey.BUILT_IN_PLAYER, SetupAction.BUILT_IN_PLAYER),
                SettingsRow.Switch("Learn game speeds", SwitchKey.LEARNING, SetupAction.LEARNING),
            ) + LOOK_CONSOLES.map { (platform, name) -> SettingsRow.Console(name, platform) } + listOf(
                // The 3DS plays only in Azahar, so it has no console page: its text source is here.
                SettingsRow.Switch("3DS text", SwitchKey.AZAHAR_TEXT, SetupAction.AZAHAR_RPC),
            ),
        ),
        SettingsGroup(
            id = "controls",
            title = "Controls",
            info = listOf(SettingsInfo.SHORTCUTS),
            rows = listOf(
                SettingsRow.Switch("A on the right", SwitchKey.FACE_A_ON_RIGHT, SetupAction.FACE_A_ON_RIGHT),
            ),
        ),
        SettingsGroup(
            id = "reading",
            title = "Reading",
            rows = listOf(
                SettingsRow.Button("Dictionaries", SetupAction.DICTIONARIES),
                SettingsRow.Button("Words", SetupAction.WORDS),
                SettingsRow.Button("Memory samples", SetupAction.YOMIKATA),
            ),
        ),
        SettingsGroup(
            id = "anki",
            title = "Anki",
            rows = listOf(
                SettingsRow.Button("Anki cards", SetupAction.ANKI),
            ),
        ),
        SettingsGroup(
            id = "connections",
            title = "Connections",
            info = listOf(SettingsInfo.ROMM_ACCOUNT),
            rows = listOf(
                SettingsRow.Button("Sync library", SetupAction.SYNC_LIBRARY),
                SettingsRow.Button("Yomikata servers", SetupAction.YOMIKATA_SERVERS),
                SettingsRow.Button("AnkiConnect", SetupAction.ANKI_SERVER),
                SettingsRow.Button("kechimochi", SetupAction.READING_LOG),
                SettingsRow.Button("Sign out of RomM", SetupAction.SIGN_OUT),
            ),
        ),
        SettingsGroup(
            id = "device",
            title = "Device",
            rows = listOf(
                SettingsRow.Switch("Genkan as Home", SwitchKey.HOME, SetupAction.ENABLE_HOME, SetupAction.DISABLE_HOME),
                SettingsRow.Button("Shizuku", SetupAction.SHIZUKU),
                SettingsRow.Button("Exact play time", SetupAction.USAGE_ACCESS),
                SettingsRow.Button("Headphones", SetupAction.HEADPHONES),
                SettingsRow.Switch("Insert animation", SwitchKey.INSERT_ANIMATION, SetupAction.INSERT_ANIMATION),
            ),
        ),
        SettingsGroup(id = "about", title = "About", info = listOf(SettingsInfo.VERSION, SettingsInfo.LICENCE), rows = emptyList()),
    )
}

enum class SettingsPane { GROUPS, ROWS }

/** Where the gamepad is on the settings page. The ViewModel owns it; Compose focus is never used. */
data class SettingsFocus(
    val pane: SettingsPane = SettingsPane.GROUPS,
    val group: Int = 0,
    val row: Int = 0,
)

/** Pure focus moves, so navigation can be tested without a screen. */
object SettingsNavigator {

    /**
     * Returns the new focus, or null when the key is not one the page handles here. B on the
     * group list is one of those: it leaves settings, which the activity does. A on a row leaves
     * the focus where it is; the caller presses the row.
     */
    fun onKey(focus: SettingsFocus, key: PadKey, groups: List<SettingsGroup>): SettingsFocus? {
        if (groups.isEmpty()) return null
        return when (focus.pane) {
            SettingsPane.GROUPS -> when (key) {
                PadKey.UP -> focus.copy(group = (focus.group - 1).coerceAtLeast(0), row = 0)
                PadKey.DOWN -> focus.copy(group = (focus.group + 1).coerceAtMost(groups.lastIndex), row = 0)
                PadKey.RIGHT, PadKey.A ->
                    if (groups[focus.group].rows.isEmpty()) focus else focus.copy(pane = SettingsPane.ROWS)
                else -> null
            }
            SettingsPane.ROWS -> when (key) {
                PadKey.UP -> focus.copy(row = (focus.row - 1).coerceAtLeast(0))
                PadKey.DOWN -> focus.copy(row = (focus.row + 1).coerceAtMost(groups[focus.group].rows.lastIndex))
                PadKey.LEFT, PadKey.B -> focus.copy(pane = SettingsPane.GROUPS)
                PadKey.A -> focus
                else -> null
            }
        }
    }
}
