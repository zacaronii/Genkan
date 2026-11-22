package app.genkan.ui

import app.genkan.library.PlatformEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleLabelTest {
    private fun console(id: Long, name: String, romCount: Int = 0) = PlatformEntity(id, "slug", null, name, romCount, null)

    @Test
    fun `RomM's count, or what's on the shelf while a filter leaves some out`() {
        val ps1 = console(7, "PlayStation", romCount = 24)
        assertEquals("PlayStation  24", consoleLabel(ps1))
        assertEquals("PlayStation  9", consoleLabel(ps1, shown = 9))
        assertEquals("PlayStation  0", consoleLabel(ps1, shown = 0))
    }

    @Test
    fun `Genkan's own shelves have no count`() {
        assertEquals("Continue playing", consoleLabel(console(-1, "Continue playing"), shown = 3))
    }

    @Test
    fun `the name says when only downloaded games are shown, on RomM's consoles only`() {
        val ps1 = console(7, "PlayStation", romCount = 24)
        assertEquals("PlayStation  9 of 24", consoleLabel(ps1, shown = 9, downloaded = true))
        // Its own list not there yet: RomM's count, as without the filter.
        assertEquals("PlayStation  24", consoleLabel(ps1, shown = null, downloaded = true))
        assertEquals("PC", consoleLabel(console(-1, "PC"), shown = 3, downloaded = true))
    }

    @Test
    fun `a shelf just switched to shows RomM's count until its own list is there`() {
        val ps1 = console(7, "PlayStation", romCount = 24)
        val ps2 = console(8, "PlayStation 2", romCount = 18)
        // The PlayStation's 12 downloaded games are still the shelf's list after the switch to the PS2.
        assertEquals("PlayStation 2  18", consoleLabel(ps2, shownCount(ps2, downloadedOnly = true, gamesFor = ps1.id, shownGames = 12)))
        // A 0 that only means the PS2's list hasn't arrived isn't shown either.
        assertEquals("PlayStation 2  18", consoleLabel(ps2, shownCount(ps2, downloadedOnly = true, gamesFor = null, shownGames = 0)))
        // Its own list: its own count, 0 included.
        assertEquals("PlayStation 2  0", consoleLabel(ps2, shownCount(ps2, downloadedOnly = true, gamesFor = ps2.id, shownGames = 0)))
        assertEquals("PlayStation  12", consoleLabel(ps1, shownCount(ps1, downloadedOnly = true, gamesFor = ps1.id, shownGames = 12)))
        // Without the filter, RomM's count always.
        assertEquals("PlayStation  24", consoleLabel(ps1, shownCount(ps1, downloadedOnly = false, gamesFor = ps1.id, shownGames = 12)))
    }
}
