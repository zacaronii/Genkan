package app.genkan.yomikata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the details' chip says about how this build reads a game's text. */
class TextSourceTest {
    private fun entry(json: String) = CatalogEntry.parse(json)

    private val layout = entry(
        """{"id": "SLPS-00001", "platform": "ps1", "encoding": "table", "tables": ["t.tbl"], "unit": 2,
            "layouts": [{"message": {"address": "0x80000000", "charset": "table", "max_bytes": 8}}]}""",
    )
    // A layout only for a version of another release: the built-in player wouldn't pick it for this one.
    private val otherVersion = entry(
        """{"id": "SLPS-00002", "platform": "ps1", "encoding": "table", "tables": ["t.tbl"], "unit": 2,
            "versions": [{"id": "SLPS-09999", "exe_sha1": "aa"}],
            "layouts": [{"versions": ["aa"], "message": {"address": "0x80000000", "charset": "table", "max_bytes": 8}}]}""",
    )
    private val tableOnly = entry("""{"id": "SLPS-00003", "platform": "ps1", "encoding": "table", "tables": ["t.tbl"], "unit": 2}""")
    private val hook = entry(
        """{"id": "NPJH00001", "platform": "psp", "encoding": "utf16le",
            "hooks": [{"address": "0x08800000", "register": "a0", "charset": "utf16le"}]}""",
    )
    private val standard = entry("""{"id": "SLPS-00004", "platform": "ps1", "encoding": "shift_jis"}""")

    // A PSP release with both a layout and a hook, and one with only a layout.
    private val pspBoth = entry(
        """{"id": "NPJH00002", "platform": "psp", "encoding": "utf16le",
            "hooks": [{"address": "0x08800000", "register": "a0", "charset": "utf16le"}],
            "layouts": [{"message": {"address": "0x08800000", "charset": "utf16le", "max_bytes": 8}}]}""",
    )
    private val pspLayout = entry(
        """{"id": "NPJH00003", "platform": "psp", "encoding": "utf16le",
            "layouts": [{"message": {"address": "0x08800000", "charset": "utf16le", "max_bytes": 8}}]}""",
    )
    private val all = listOf(layout, otherVersion, tableOnly, hook, standard, pspBoth, pspLayout).associateBy { it.id }

    /** The source with the built-in player ([builtIn]) or in the emulator app. */
    private fun source(id: String?, tablesHere: Boolean = true, builtIn: Boolean = true) = TextSource.of(id, all::get, { tablesHere }, builtIn)

    @Test
    fun `a layout this build would use says layout`() {
        assertEquals(TextSource.LAYOUT, source("SLPS-00001"))
    }

    @Test
    fun `a layout this build wouldn't pick falls to the table`() {
        assertEquals(TextSource.TABLE, source("SLPS-00002"))
    }

    @Test
    fun `a table the catalog marks partial says so`() {
        val partial = entry("""{"id": "SLPS-00005", "platform": "ps1", "encoding": "table", "tables": ["t.tbl"], "unit": 2, "status": "partial"}""")
        assertEquals(TextSource.TABLE_PARTIAL, TextSource.of("SLPS-00005", { partial }, { true }, builtIn = true))
        assertEquals("Text: table (partial)", TextSource.TABLE_PARTIAL.label)
    }

    @Test
    fun `tables say table only when their files are on the device`() {
        assertEquals(TextSource.TABLE, source("SLPS-00003"))
        assertNull(source("SLPS-00003", tablesHere = false))
    }

    @Test
    fun `in PPSSPP a PSP hook says hook, and a layout alone says nothing`() {
        assertEquals(TextSource.HOOK, source("NPJH00001", builtIn = false))
        assertEquals(TextSource.HOOK, source("NPJH00002", builtIn = false))
        assertNull(source("NPJH00003", builtIn = false))
    }

    @Test
    fun `in the built-in player a PSP layout is read, and a hook isn't`() {
        assertEquals(TextSource.LAYOUT, source("NPJH00002"))
        assertNull(source("NPJH00001"))
    }

    @Test
    fun `in an emulator app a PS1 layout or table reads nothing`() {
        assertNull(source("SLPS-00001", builtIn = false))
        assertNull(source("SLPS-00003", builtIn = false))
    }

    @Test
    fun `no chip without an entry, without an id, or for a standard entry with nothing more`() {
        assertNull(source("SLPS-99999"))
        assertNull(source(null))
        assertNull(source("SLPS-00004"))
    }
}
