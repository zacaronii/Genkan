package app.genkan.yomikata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogEntryTest {

    // Shaped like the catalog's Tokimeki Memorial 4 entry: three hooks, one a filter.
    private val tokimeki = CatalogEntry.parse(
        """
        {"id": "NPJH50127", "platform": "psp", "title": "ときめきメモリアル4", "encoding": "shift_jis",
         "hooks": [
           {"address": "0x088719DC", "register": "a1", "charset": "shift_jis",
            "cleanup": [{"regex": "(?m)^�.*$|e_voice", "replace": ""}]},
           {"address": "0x0899A510", "register": "a2", "charset": "shift_jis",
            "cleanup": [{"regex": "\\\\n", "replace": "\n"}]},
           {"address": "0x08850270", "register": "a1", "charset": "shift_jis",
            "cleanup": [{"regex": "(?s)^.*[…。！？]$", "replace": ""}]}],
         "status": "partial"}
        """,
    )

    @Test
    fun `hooks parse with their addresses and cleanup`() {
        assertEquals(3, tokimeki.hooks.size)
        assertEquals(0x088719DCL, tokimeki.hooks[0].address)
        assertEquals("a2", tokimeki.hooks[1].register)
        assertEquals("a\nb", tokimeki.clean("a\\nb", tokimeki.hooks[1]))
    }

    @Test
    fun `a line cleaned to nothing is dropped`() {
        val choices = tokimeki.hooks[2]
        assertEquals("一緒に帰る", tokimeki.clean("一緒に帰る", choices))
        assertNull(tokimeki.clean("今日はいい天気だね。", choices))
    }

    @Test
    fun `group references, remaps and dollar signs`() {
        val entry = CatalogEntry.parse(
            """
            {"id": "ULJM06232", "platform": "psp", "title": "t", "encoding": "shift_jis", "status": "partial",
             "cleanup": [{"remap": {"ｺ": "こ", "｡": "。"}}, {"regex": "\\${'$'}t(.*?)@", "replace": "${'$'}1"},
                         {"regex": "円", "replace": "${'$'}${'$'}"}]}
            """,
        )
        assertEquals("こ。天音です", entry.clean("ｺ｡\$t天音@です"))
        assertEquals("\$100", entry.clean("円100"))
    }

    @Test
    fun `cleanup gives what the catalog's reference implementation gives`() {
        // Expected values from tools/src/yomikata/cleanup.py.
        val entry = CatalogEntry.parse(
            """
            {"id": "NPJH50372", "platform": "psp", "title": "t", "encoding": "utf16le", "status": "auto",
             "cleanup": [{"regex": "<CLT[^<>\\n]*>", "replace": ""}, {"regex": "\\n", "replace": ""},
                         {"regex": "^\\s+|\\s+${'$'}", "replace": ""}]}
            """,
        )
        assertEquals("ボクの名前は“苗木誠”だ。", entry.clean("<CLT 4>ボクの名前は<CLT 3>“苗木誠”<CLT>だ。\n<CLT>"))
        assertEquals("あ", entry.clean("\u3000あ\u3000")) // \s takes the ideographic space, as in Python
        val unmatched = CatalogEntry.parse(
            """{"id": "x", "platform": "psp", "title": "t", "encoding": "utf8", "status": "auto",
                "cleanup": [{"regex": "(x)?b", "replace": "[${'$'}1]${'$'}${'$'}"}]}""",
        )
        assertEquals("a[]${'$'}", unmatched.clean("ab"))
        assertNull(unmatched.clean("  ")) // only spaces left: dropped
    }

    @Test
    fun `versions say which executables an entry fits`() {
        val entry = CatalogEntry.parse(
            """{"id": "SLPS-01001", "platform": "ps1", "title": "t", "encoding": "table", "status": "partial",
                "versions": [{"exe": "SLPS_010.01", "exe_sha1": "a8b3924464bf4b2977f03ac14ea8a7e0c09739d7"}]}""",
        )
        assertTrue(entry.fits("A8B3924464BF4B2977F03AC14EA8A7E0C09739D7"))
        assertFalse(entry.fits("0000000000000000000000000000000000000000"))
        assertTrue(tokimeki.fits("anything")) // no versions listed: nothing to check against
    }

    @Test
    fun `tables decode codes, controls and gaps`() {
        val kana = "0001=あ\n0002=い\n# comment\n*FFFE\n/FFFF\n"
        val game = "0002=う\n0100=学\n"
        val table = CharTable.parse(listOf(kana, game)) // later files win
        val bytes = byteArrayOf(0x00, 0x01, 0x00, 0x02, 0xFF.toByte(), 0xFE.toByte(), 0x01, 0x00, 0x00, 0x03, 0xFF.toByte(), 0xF0.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals("あう\n学�\n\n", table.decode(bytes, unit = 2, controls = mapOf("FFF0" to "wait")))
        assertEquals(3, table.size)
    }

    @Test
    fun `an entry's tuning gives each core's step and where it was measured`() {
        // A made-up release: no game's real id.
        val entry = CatalogEntry.parse(
            """
            {"id": "SLPM-12345", "platform": "ngc", "encoding": "shift_jis",
             "tuning": {"dolphin": {"step": "vi-skip", "measured_on": "Made-up Handheld"},
                        "ppsspp": {"step": "960x544"},
                        "broken": {"measured_on": "no step"}}}
            """,
        )
        assertEquals(app.genkan.core.BundledTuning("vi-skip", "Made-up Handheld"), entry.tuning["dolphin"])
        assertEquals(app.genkan.core.BundledTuning("960x544", null), entry.tuning["ppsspp"])
        assertFalse("broken" in entry.tuning)
        assertTrue(CatalogEntry.parse("""{"id": "SLPM-12345", "platform": "ngc", "encoding": "shift_jis"}""").tuning.isEmpty())
    }

    @Test
    fun `a Wii entry's controller is one of the three, else not said`() {
        // Made-up releases: no game's real id.
        fun controller(field: String) = CatalogEntry.parse("""{"id": "RZZJ01", "platform": "wii", "encoding": "shift_jis"$field}""").wiiController
        assertEquals("classic", controller(""", "wii_controller": "classic""""))
        assertEquals("sideways", controller(""", "wii_controller": "sideways""""))
        assertEquals("nunchuk", controller(""", "wii_controller": "nunchuk""""))
        assertNull(controller(""", "wii_controller": "balance_board""""))
        assertNull(controller(""))
    }
}
