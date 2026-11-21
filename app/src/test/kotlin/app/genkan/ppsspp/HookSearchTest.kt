package app.genkan.ppsspp

import app.genkan.yomikata.CatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookSearchTest {

    // Danganronpa (NPJH50372) keeps each line in its script with colour tags around it.
    private val raws = listOf(
        "<CLT 4>ボクは立っていた…\n<CLT>",
        "まずはオーソドックスに自己紹介から始めたいと思う…\nボクの名前は<CLT 3>“苗木誠”<CLT>だ。\n",
    )
    private val ocrs = listOf("ボクは立っていた…", "まずはオーソドックスに自己紹介から始めたいと思う…ボクの名前は“苗木誠”だ。")

    @Test
    fun `similarity ignores layout and tolerates a misread`() {
        assertEquals(1.0, HookSearch.similarity("ボクは\n立っていた…", "ボクは 立っていた…"), 0.0)
        assertTrue(HookSearch.similarity("ボクは立っていた…", "ボクは立つていた…") > 0.8)
        assertTrue(HookSearch.similarity("ボクは立っていた…", "国の将来を担う") < 0.2)
    }

    @Test
    fun `a line fits in OCR text that also holds the speaker's name`() {
        assertEquals(1.0, HookSearch.fit("中身の方も同じ…", "エギ・マコト中身の方も同じ…"), 0.0)
        assertTrue(HookSearch.fit("中身の方も同じ…", "エギマコト中身の方も同じ") > 0.8)
        // The whole script from there on is not the line.
        assertTrue(HookSearch.fit("中身の方も同じ…性格にも特技にも成績にも", "中身の方も同じ…") < 0.5)
    }

    @Test
    fun `probes come from the start, middle and end`() {
        assertEquals(listOf("まるで…そ", "界の中心で", "かのように"), HookSearch.probes("まるで…そこが世界の中心でもあるかのように"))
        assertEquals(listOf("ボクは"), HookSearch.probes("ボクは"))
    }

    @Test
    fun `bytes are found where they occur`() {
        val ram = byteArrayOf(0, 1, 2, 3, 1, 2, 9)
        assertEquals(listOf(1, 4), HookSearch.indexesOf(ram, byteArrayOf(1, 2)))
        assertEquals(emptyList<Int>(), HookSearch.indexesOf(ram, byteArrayOf(2, 1)))
    }

    @Test
    fun `cleanup takes out the game's tags and line breaks`() {
        val rules = HookSearch.cleanupFor(raws, ocrs)
        assertEquals(3, rules.length())
        assertEquals("<CLT[^<>\\n]*>", rules.getJSONObject(0).getString("regex"))
        raws.zip(ocrs).forEach { (raw, ocr) -> assertEquals(ocr, HookSearch.clean(raw, rules)) }
        // A line already plain needs nothing.
        assertEquals(0, HookSearch.cleanupFor(listOf("ボクは立っていた…"), listOf("ボクは立っていた…")).length())
    }

    @Test
    fun `a candidate wins after three lines running, and a busy entry is dropped`() {
        val tracker = HookSearch.Tracker()
        val script = 0x0885AF64L
        val renderer = 0x08812000L
        val lines = listOf("まるで…そこが世界の中心でもあるかのように…", "何百年という歴史を持ち、", "そんな学園への入学資格は２つ…")
        lines.forEachIndexed { i, line ->
            val hits = mapOf(
                script to listOf(HookSearch.Hit(mapOf("a0" to "<CLT 4>$line\n<CLT>", "a1" to "前の行"))),
                renderer to List(40) { HookSearch.Hit(mapOf("a0" to line)) },
            )
            val winner = tracker.line(line, hits)
            if (i < 2) assertNull(winner) else assertEquals(HookSearch.Tracker.Candidate(script, "a0"), winner)
        }
        assertTrue(renderer in tracker.rejected)
    }

    @Test
    fun `a missed line starts the count again`() {
        val tracker = HookSearch.Tracker()
        val c = HookSearch.Tracker.Candidate(1L, "a0")
        tracker.line("一行目です", mapOf(1L to listOf(HookSearch.Hit(mapOf("a0" to "一行目です")))))
        tracker.line("二行目です", emptyMap())
        assertEquals(0, tracker.streak(c))
    }

    @Test
    fun `the report has the fields docs-hook-finder lists`() {
        val rules = HookSearch.cleanupFor(raws, ocrs)
        val hook = CatalogEntry.Hook(0x0885AF64L, "a0", "utf16le", emptyList(), emptyList())
        val pairs = listOf(HookSearch.Checked("ボクは立っていた…", "ボクは立っていた…"), HookSearch.Checked("国の将来", "全然違う"))
        val report = HookSearch.report("NPJH50372", "ダンガンロンパ", "1.03", hook, "utf16le", rules, pairs, "Genkan", "0.0.1", "v1.20.4")
        assertEquals("0x0885AF64", report.getJSONObject("hook").getString("address"))
        assertEquals("1.03", report.getString("disc_version"))
        val evidence = report.getJSONObject("evidence")
        assertEquals(2, evidence.getInt("lines_checked"))
        assertEquals(1, evidence.getInt("lines_matched"))
        assertFalse(pairs[1].matches())
    }

    @Test
    fun `a function starts at its stack frame`() {
        assertTrue(HookSearch.isFrameSetup(0x27BDFFB0)) // addiu sp, sp, -0x50
        assertFalse(HookSearch.isFrameSetup(0x27BD0050)) // addiu sp, sp, 0x50: the epilogue
    }
}
