package app.genkan.ui

import app.genkan.input.PadKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsGroupsTest {

    private val groups = SettingsGroups.DEFAULT

    @Test
    fun `every action has exactly one row`() {
        val reachable = groups.flatMap { group -> group.rows.flatMap { it.actions } }
        assertEquals(SetupAction.entries.toSet(), reachable.toSet())
        assertEquals(reachable.distinct(), reachable)
    }

    @Test
    fun `Full box is a switch in every build, with the library`() {
        val library = groups.single { it.id == "library" }
        val row = library.rows.filterIsInstance<SettingsRow.Switch>().single { it.key == SwitchKey.FULL_BOX }
        assertEquals("Full box", row.title)
        assertEquals(SetupAction.FULL_BOX, row.action(on = true))
        assertEquals(SetupAction.FULL_BOX, row.action(on = false))
    }

    @Test
    fun `group ids are unique`() {
        assertEquals(groups.size, groups.map { it.id }.toSet().size)
    }

    @Test
    fun `Home is one switch that turns on and off with different actions`() {
        val home = groups.flatMap { it.rows }.filterIsInstance<SettingsRow.Switch>().single { it.key == SwitchKey.HOME }
        assertEquals(SetupAction.ENABLE_HOME, home.action(on = false))
        assertEquals(SetupAction.DISABLE_HOME, home.action(on = true))
    }

    @Test
    fun `a press runs what the switch shows, by pad or by touch`() {
        fun state(home: Boolean, player: Boolean) = SetupState(
            status = app.genkan.home.DualScreenStatus(isDefaultHome = home),
            romm = app.genkan.romm.RomMState.SignedOut,
            sync = app.genkan.library.SyncStatus.Idle,
            usageAccess = true,
            shizuku = app.genkan.privileged.Privileged.State.READY,
            pspDebugger = false,
            azaharRpc = false,
            builtInPlayer = player,
            textSamples = false,
            dictionaries = emptyList(),
        )
        val switches = groups.flatMap { it.rows }.filterIsInstance<SettingsRow.Switch>()
        val home = switches.single { it.key == SwitchKey.HOME }
        val player = switches.single { it.key == SwitchKey.BUILT_IN_PLAYER }
        assertEquals(SetupAction.DISABLE_HOME, state(home = true, player = false).actionFor(home))
        assertEquals(SetupAction.ENABLE_HOME, state(home = false, player = false).actionFor(home))
        assertEquals(SetupAction.BUILT_IN_PLAYER, state(home = false, player = true).actionFor(player))
    }

    private fun move(focus: SettingsFocus, key: PadKey) = SettingsNavigator.onKey(focus, key, groups)

    @Test
    fun `up and down on the group list switch group and reset the row`() {
        val next = move(SettingsFocus(group = 1, row = 2), PadKey.DOWN)!!
        assertEquals(2, next.group)
        assertEquals(0, next.row)
        assertEquals(0, move(SettingsFocus(), PadKey.UP)!!.group)
        assertEquals(groups.lastIndex, move(SettingsFocus(group = groups.lastIndex), PadKey.DOWN)!!.group)
    }

    @Test
    fun `right or A enters the rows, left or B goes back to the groups`() {
        assertEquals(SettingsPane.ROWS, move(SettingsFocus(), PadKey.RIGHT)!!.pane)
        assertEquals(SettingsPane.ROWS, move(SettingsFocus(), PadKey.A)!!.pane)
        val inRows = SettingsFocus(pane = SettingsPane.ROWS, group = 1, row = 1)
        assertEquals(inRows.copy(pane = SettingsPane.GROUPS), move(inRows, PadKey.LEFT))
        assertEquals(inRows.copy(pane = SettingsPane.GROUPS), move(inRows, PadKey.B))
    }

    @Test
    fun `B on the group list is left to the activity`() {
        assertNull(move(SettingsFocus(), PadKey.B))
    }

    @Test
    fun `rows stop at the ends of their group and A leaves the focus alone`() {
        val last = groups[1].rows.lastIndex
        val inRows = SettingsFocus(pane = SettingsPane.ROWS, group = 1, row = last)
        assertEquals(last, move(inRows, PadKey.DOWN)!!.row)
        assertEquals(0, move(inRows.copy(row = 0), PadKey.UP)!!.row)
        assertEquals(inRows, move(inRows, PadKey.A))
    }
}
