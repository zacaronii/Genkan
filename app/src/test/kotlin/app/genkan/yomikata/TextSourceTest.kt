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
    private val catalog = listOf(layout, otherVersion, tableOnly, hook, standard).associateBy { it.id }

    private fun source(id: String?, tablesHere: Boolean = true) = TextSource.of(id, catalog::get) { tablesHere }

    @Test
    fun `a layout this build would use says layout`() {
        assertEquals(TextSource.LAYOUT, source("SLPS-00001"))
    }

    @Test
    fun `a layout this build wouldn't pick falls to the table`() {
        assertEquals(TextSource.TABLE, source("SLPS-00002"))
    }

    @Test
    fun `tables say table only when their files are on the device`() {
        assertEquals(TextSource.TABLE, source("SLPS-00003"))
        assertNull(source("SLPS-00003", tablesHere = false))
    }

    @Test
    fun `a PSP hook says hook`() {
        assertEquals(TextSource.HOOK, source("NPJH00001"))
    }

    @Test
    fun `no chip without an entry, without an id, or for a standard entry with nothing more`() {
        assertNull(source("SLPS-99999"))
        assertNull(source(null))
        assertNull(source("SLPS-00004"))
    }
}
