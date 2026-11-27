package app.genkan.ui

import app.genkan.core.PadChords
import app.genkan.input.PadKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHelpTest {

    private val groups = SettingsGroups.DEFAULT
    private fun List<ButtonHint>.text() = joinToString(" | ") { h -> h.buttons.joinToString("/") { it.label } + " " + h.action }
    private val rows = groups.flatMap { it.rows }

    @Test
    fun `the rows whose line leaves things out have help, a console's too`() {
        val switches = rows.filterIsInstance<SettingsRow.Switch>().associateBy { it.key }
        listOf(SwitchKey.BUILT_IN_PLAYER, SwitchKey.FACE_A_ON_RIGHT, SwitchKey.LEARNING, SwitchKey.AZAHAR_TEXT, SwitchKey.HOME)
            .forEach { assertNotNull(it.name, SettingsHelp.of(switches.getValue(it))) }
        val buttons = rows.filterIsInstance<SettingsRow.Button>().associateBy { it.action }
        listOf(SetupAction.SHIZUKU, SetupAction.USAGE_ACCESS, SetupAction.YOMIKATA_SERVERS, SetupAction.ANKI_SERVER)
            .forEach { assertNotNull(it.name, SettingsHelp.of(buttons.getValue(it))) }
        assertNotNull(SettingsHelp.of(rows.filterIsInstance<SettingsRow.Console>().first()))
        assertNull(SettingsHelp.of(buttons.getValue(SetupAction.SIGN_OUT)))
    }

    @Test
    fun `the shortcuts table has every one of the player's shortcuts, and the DS stick click`() {
        val keys = SettingsHelp.SHORTCUTS.map { it.first }
        assertEquals(PadChords.DEFAULTS.size + 1, keys.size)
        assertTrue(keys.all { it.startsWith("Select + ") || it == "Stick click" })
        assertTrue("Select + Start" in keys && "Select + R1" in keys && "Select + left" in keys)
    }

    private fun focusOn(action: SetupAction): SettingsFocus {
        val group = groups.indexOfFirst { g -> g.rows.any { action in it.actions } }
        return SettingsFocus(pane = SettingsPane.ROWS, group = group, row = groups[group].rows.indexOfFirst { action in it.actions })
    }

    @Test
    fun `Y opens a row's help where it has one, and B, Y or A close it, nothing else moving under it`() {
        val shizuku = focusOn(SetupAction.SHIZUKU)
        val open = SettingsNavigator.onKey(shizuku, PadKey.Y, groups)!!
        assertTrue(open.help)
        assertEquals(open, SettingsNavigator.onKey(open, PadKey.DOWN, groups))
        listOf(PadKey.B, PadKey.Y, PadKey.A).forEach { assertEquals(shizuku, SettingsNavigator.onKey(open, it, groups)) }
        val signOut = focusOn(SetupAction.SIGN_OUT)
        assertFalse(SettingsNavigator.onKey(signOut, PadKey.Y, groups)!!.help)
    }

    @Test
    fun `the hints offer Y where there is help, and B to close it`() {
        assertEquals("A Shizuku | Y Help | B Groups | Start Library", Hints.settings(onRows = true, rowAction = "Shizuku", help = true).text())
        assertEquals("B Close", Hints.settingsHelp().text())
    }
}
