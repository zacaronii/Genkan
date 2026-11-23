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

/** A status line with nothing to press, shown above a group's rows. */
enum class SettingsInfo { ROMM_ACCOUNT, YOMIKATA_SAMPLES, SECOND_SCREEN, COMPANION }

/** The on/off settings a switch row can show. */
enum class SwitchKey { HOME, BUILT_IN_PLAYER, INSERT_ANIMATION, PSP_TEXT, AZAHAR_TEXT, FULL_BOX }

/** A row of a group, the only things the gamepad lands on. */
sealed interface SettingsRow {
    val title: String

    /** Every action this row can run, whatever state it is in. */
    val actions: Set<SetupAction>

    /** A row that does one thing when pressed: open a page, start a sync, ask for a grant. */
    data class Button(override val title: String, val action: SetupAction) : SettingsRow {
        override val actions get() = setOf(action)
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

/** The settings page's groups, in order. */
object SettingsGroups {
    val DEFAULT: List<SettingsGroup> = listOf(
        SettingsGroup(
            id = "library",
            title = "Library & account",
            info = listOf(SettingsInfo.ROMM_ACCOUNT),
            rows = listOf(
                SettingsRow.Button("Sync library now", SetupAction.SYNC_LIBRARY),
                SettingsRow.Switch("Full box", SwitchKey.FULL_BOX, SetupAction.FULL_BOX),
                SettingsRow.Button("Sign out of RomM", SetupAction.SIGN_OUT),
            ),
        ),
        SettingsGroup(
            id = "playing",
            title = "Playing",
            rows = listOf(
                SettingsRow.Switch("Built-in player", SwitchKey.BUILT_IN_PLAYER, SetupAction.BUILT_IN_PLAYER),
                SettingsRow.Button("Screen look", SetupAction.SCREEN_LOOK),
                SettingsRow.Switch("Insert animation", SwitchKey.INSERT_ANIMATION, SetupAction.INSERT_ANIMATION),
                SettingsRow.Button("Emulator saves through Shizuku", SetupAction.SHIZUKU),
                SettingsRow.Button("Usage access for play time", SetupAction.USAGE_ACCESS),
            ),
        ),
        SettingsGroup(
            id = "reading",
            title = "Reading text",
            info = listOf(SettingsInfo.YOMIKATA_SAMPLES),
            rows = listOf(
                SettingsRow.Switch("PSP text from PPSSPP", SwitchKey.PSP_TEXT, SetupAction.PSP_DEBUGGER),
                SettingsRow.Switch("3DS text from Azahar", SwitchKey.AZAHAR_TEXT, SetupAction.AZAHAR_RPC),
                SettingsRow.Button("Yomikata", SetupAction.YOMIKATA),
            ),
        ),
        SettingsGroup(
            id = "words",
            title = "Dictionaries & words",
            rows = listOf(
                SettingsRow.Button("Dictionaries", SetupAction.DICTIONARIES),
                SettingsRow.Button("Words", SetupAction.WORDS),
            ),
        ),
        SettingsGroup(
            id = "anki",
            title = "Anki & reading log",
            rows = listOf(
                SettingsRow.Button("Anki", SetupAction.ANKI),
                SettingsRow.Button("Reading log", SetupAction.READING_LOG),
            ),
        ),
        SettingsGroup(
            id = "device",
            title = "Device setup",
            info = listOf(SettingsInfo.SECOND_SCREEN, SettingsInfo.COMPANION),
            rows = listOf(
                SettingsRow.Switch("Genkan as Home", SwitchKey.HOME, SetupAction.ENABLE_HOME, SetupAction.DISABLE_HOME),
            ),
        ),
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
