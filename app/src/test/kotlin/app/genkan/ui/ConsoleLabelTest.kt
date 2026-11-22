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
}
