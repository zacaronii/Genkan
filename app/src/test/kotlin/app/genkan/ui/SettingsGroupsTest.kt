package app.genkan.ui

import app.genkan.input.PadKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGroupsTest {

    private val groups = SettingsGroups.DEFAULT

    @Test
    fun `every action has exactly one row, or a console page, and only a console's page is opened from many`() {
        val reachable = groups.flatMap { group -> group.rows.flatMap { it.actions } }.filter { it != SetupAction.CONSOLE }
        assertEquals(SetupAction.entries.toSet() - SetupAction.CONSOLE, reachable.toSet() + SettingsGroups.ON_CONSOLE_PAGES)
        assertEquals(reachable.distinct(), reachable)
        assertTrue(SettingsGroups.ON_CONSOLE_PAGES.none { it in reachable })
        assertTrue(groups.flatMap { it.rows }.any { it is SettingsRow.Console })
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
    fun `the groups run from the status card to About, the servers in one place`() {
        assertEquals(
            listOf("status", "library", "consoles", "controls", "reading", "anki", "connections", "device", "storage", "advanced", "about"),
            groups.map { it.id },
        )
        assertEquals(listOf(SettingsInfo.STATUS), groups.first().info)
        val connections = groups.single { it.id == "connections" }.rows.flatMap { it.actions }
        assertEquals(
            setOf(SetupAction.SYNC_LIBRARY, SetupAction.YOMIKATA_SERVERS, SetupAction.ANKI_SERVER, SetupAction.READING_LOG, SetupAction.SIGN_OUT),
            connections.toSet(),
        )
    }

    @Test
    fun `the servers moved to Connections, and what was left of their pages stayed in its group`() {
        fun actionsOf(id: String) = groups.single { it.id == id }.rows.flatMap { it.actions }.toSet()
        // Yomikata's catalog and inbox, AnkiConnect (with the word-audio source) and kechimochi: Connections.
        assertTrue(actionsOf("connections").containsAll(setOf(SetupAction.YOMIKATA_SERVERS, SetupAction.ANKI_SERVER, SetupAction.READING_LOG)))
        // The memory samples are reading; the deck, note type and word marks are Anki's.
        assertTrue(SetupAction.YOMIKATA in actionsOf("reading"))
        assertEquals(setOf(SetupAction.ANKI), actionsOf("anki"))
    }

    @Test
    fun `titles are four words at most`() {
        val titles = groups.map { it.title } + groups.flatMap { it.rows }.map { it.title }
        titles.forEach { assertTrue(it, it.split(" ").size <= 4) }
    }

    @Test
    fun `a group with nothing to press keeps the pad on the group list`() {
        val status = SettingsFocus(group = 0)
        assertEquals(status, move(status, PadKey.RIGHT))
        assertEquals(status, move(status, PadKey.A))
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
        val library = SettingsFocus(group = 1)
        assertEquals(SettingsPane.ROWS, move(library, PadKey.RIGHT)!!.pane)
        assertEquals(SettingsPane.ROWS, move(library, PadKey.A)!!.pane)
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
