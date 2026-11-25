package app.genkan.yomikata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the details' chip says about how this build reads a game's text. */
class TextSourceTest {
    private fun entry(json: String) = CatalogEntry.parse(json)

    private val scripted = entry("""{"id": "SLPS-00001", "platform": "ps1", "encoding": "table", "tables": ["t.tbl"], "unit": 2}""")
    private val tableOnly = entry("""{"id": "SLPS-00003", "platform": "ps1", "encoding": "table", "tables": ["t.tbl"], "unit": 2}""")
    private val hook = entry(
        """{"id": "NPJH00001", "platform": "psp", "encoding": "utf16le",
            "hooks": [{"address": "0x08800000", "register": "a0", "charset": "utf16le"}]}""",
    )
    private val standard = entry("""{"id": "SLPS-00004", "platform": "ps1", "encoding": "shift_jis"}""")
    // A PSP release with both a script and a hook, and a PS2 one with a script.
    private val pspBoth = entry(
        """{"id": "NPJH00002", "platform": "psp", "encoding": "utf16le",
            "hooks": [{"address": "0x08800000", "register": "a0", "charset": "utf16le"}]}""",
    )
    private val ps2 = entry("""{"id": "SLPM-12345", "platform": "ps2", "encoding": "shift_jis"}""")
    private val all = listOf(scripted, tableOnly, hook, standard, pspBoth, ps2).associateBy { it.id }
    private val scripts = setOf("SLPS-00001", "NPJH00002", "SLPM-12345")

    /** The source with the built-in player ([builtIn]) or in the emulator app. */
    private fun source(id: String?, tablesHere: Boolean = true, builtIn: Boolean = true) =
        TextSource.of(id, all::get, { tablesHere }, builtIn, scripts::contains)

    @Test
    fun `a script the index lists says script, before the table`() {
        assertEquals(TextSource.SCRIPT, source("SLPS-00001"))
        assertEquals("Text: script", TextSource.SCRIPT.label)
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
    fun `in PPSSPP a PSP hook says hook, the script being the built-in player's`() {
        assertEquals(TextSource.HOOK, source("NPJH00001", builtIn = false))
        assertEquals(TextSource.HOOK, source("NPJH00002", builtIn = false))
    }

    @Test
    fun `in the built-in player a PSP script is read, and a hook isn't`() {
        assertEquals(TextSource.SCRIPT, source("NPJH00002"))
        assertNull(source("NPJH00001"))
    }

    @Test
    fun `in an emulator app a PS1 script or table reads nothing, a PS2 script does in ARMSX2`() {
        assertNull(source("SLPS-00001", builtIn = false))
        assertNull(source("SLPS-00003", builtIn = false))
        assertEquals(TextSource.SCRIPT, source("SLPM-12345", builtIn = false))
    }

    @Test
    fun `no chip without an entry, without an id, or for a standard entry with nothing more`() {
        assertNull(source("SLPS-99999"))
        assertNull(source(null))
        assertNull(source("SLPS-00004"))
    }
}
