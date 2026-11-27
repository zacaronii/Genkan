package app.genkan.ui

import app.genkan.core.PadChords
import android.view.KeyEvent

/**
 * What Y shows on a settings row: the longer help its one line leaves out. Only rows with more to
 * say than their line have any.
 */
object SettingsHelp {

    fun of(row: SettingsRow): String? = when (row) {
        is SettingsRow.Switch -> switches[row.key]
        is SettingsRow.Button -> buttons[row.action]
        is SettingsRow.Console -> CONSOLE
    }

    private const val CONSOLE =
        "The console's look (how the built-in player draws its picture), its sound stream, its face buttons, what " +
            "plays it where there's a choice, and for the PSP and PS2 where its text comes from. A game can have a look " +
            "of its own in its details, and the reader's Look chip tries others while playing; Keep makes the one on " +
            "screen that game's own."

    private val switches = mapOf(
        SwitchKey.BUILT_IN_PLAYER to
            "Games of the consoles listed under Consoles run inside Genkan, on the top screen, with the reader on the " +
            "bottom one. Off, every game starts in its installed emulator app. Each console's page says what plays it " +
            "where there's a choice (the PS2 can play in ARMSX2 instead).",
        SwitchKey.LEARNING to
            "The player keeps each game at full speed: when one falls behind it tries a lighter setting, and when one " +
            "has room it tries a sharper picture, keeping a change only if the game runs as well. What it learns is the " +
            "game's own, on its details, with a button to reset it. It doesn't run on a PS2 game drawn with Vulkan.",
        SwitchKey.FACE_A_ON_RIGHT to
            "The launcher follows your device's own button labels; the built-in player goes by position, giving each " +
            "game the buttons where its console's pad had them: the right one is A on Nintendo consoles and circle on " +
            "PlayStation, the bottom one B and cross. It assumes your device's A is at the bottom (Xbox layout) unless " +
            "this is on. A console's page can swap A/B and X/Y for it alone.",
        SwitchKey.FULL_BOX to
            "The focused game on the shelf is drawn as its whole box, front and spine, where its scans have both; off, " +
            "in a plain case with its cover.",
        SwitchKey.PREFETCH_MANUALS to
            "After each sync, installed games' manuals are fetched in the background, so they open offline. Off, a " +
            "manual is fetched the first time you open it.",
        SwitchKey.AZAHAR_TEXT to
            "3DS games play in Azahar, and their text is read by OCR. On, after OCR reads a line Genkan follows it in " +
            "Azahar's memory, through its RPC server, which this opens to your network while a game runs.",
        SwitchKey.HOME to
            "Genkan answers the Home button, with the companion on the second screen. Switching off opens Android's " +
            "Home settings to pick another launcher.",
        SwitchKey.INSERT_ANIMATION to
            "Starting a game slides its disc or cartridge into the machine on the bottom screen first. Any button skips it.",
    )

    private val buttons = mapOf(
        SetupAction.SHIZUKU to
            "DuckStation and Dolphin keep their saves in their own app folders, which other apps can't open. Shizuku " +
            "lets Genkan reach them to sync those saves with RomM. It stops at every reboot: start it again in its app.",
        SetupAction.USAGE_ACCESS to
            "With usage access, play time counts only while the game is in front. Without it, play time runs from the " +
            "launch until Genkan is back, sleep and other apps included.",
        SetupAction.HEADPHONES to
            "Your paired Bluetooth headphones, a press to connect one without going through Android's settings; at once " +
            "while Shizuku is running.",
        SetupAction.YOMIKATA_SERVERS to
            "The catalog says how each game's text is stored, fetched from its GitHub repository (a read-only token while " +
            "it's private). The inbox is your own Yomikata server, which memory samples go to after each play session.",
        SetupAction.YOMIKATA to
            "When a built-in player line isn't in memory as Shift-JIS or UTF-16, Genkan saves a snapshot of the game's " +
            "RAM with what OCR read, for your inbox to build the game's character table from. They hold script text, " +
            "so they go only to your own inbox.",
        SetupAction.ANKI_SERVER to
            "Cards go to AnkiConnect: desktop Anki with the AnkiConnect add-on, listening on 0.0.0.0. Cards made " +
            "offline wait here and are sent when it answers. Word audio, if set, is a Yomitan audio source URL with " +
            "{term} and {reading}.",
        SetupAction.READING_LOG to
            "Genkan counts reading time and characters per game. With a mochibridge address, each session is logged in " +
            "kechimochi; until then sessions are kept on this device.",
        SetupAction.SYNC_LIBRARY to
            "Fetches RomM's games, covers and saves now. Genkan also syncs on its own as it starts.",
    )

    /** The built-in player's shortcuts, as Settings › Controls lists them: Select held with another button. */
    val SHORTCUTS: List<Pair<String, String>> = PadChords.DEFAULTS.entries
        .sortedBy { PadChords.Shortcut.entries.indexOf(it.value) }
        .map { (key, shortcut) -> "Select + ${buttonName(key)}" to shortcutName(shortcut) } +
        ("Stick click" to "On a DS game, swap its lower screen and the reader")

    private fun buttonName(key: Int): String = when (key) {
        KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
        KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_START -> "Start"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"
        else -> KeyEvent.keyCodeToString(key).removePrefix("KEYCODE_")
    }

    private fun shortcutName(shortcut: PadChords.Shortcut): String = when (shortcut) {
        PadChords.Shortcut.SAVE_STATE -> "Save a state in the slot"
        PadChords.Shortcut.LOAD_STATE -> "Load the slot's state"
        PadChords.Shortcut.NEXT_SLOT -> "Next slot"
        PadChords.Shortcut.PREVIOUS_SLOT -> "Previous slot"
        PadChords.Shortcut.FAST_FORWARD -> "Fast-forward while held"
        PadChords.Shortcut.QUIT -> "Quit, saving the game"
    }
}
