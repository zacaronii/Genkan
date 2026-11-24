package app.genkan.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MemoryScanTest {

    @Test
    fun `finds every occurrence, UTF-16 only on even offsets`() {
        val text = "次の漢字の読み".toByteArray(Charsets.UTF_16LE)
        val haystack = byteArrayOf(1) + text + byteArrayOf(0, 0) + text
        val needle = "漢字".toByteArray(Charsets.UTF_16LE)
        assertEquals(listOf(5, 21), MemoryScan.find(haystack, needle))
        assertEquals(emptyList<Int>(), MemoryScan.find(haystack, needle, aligned = true))
        assertEquals(listOf(4, 20), MemoryScan.find(haystack.copyOfRange(1, haystack.size), needle, aligned = true))
    }

    @Test
    fun `probes are short runs from the start, middle and end of a line`() {
        val probes = MemoryScan.probes("データを選択してください")
        assertEquals("データを", probes.first())
        assertEquals("ください", probes.last())
        assertEquals(listOf("あい"), MemoryScan.probes("あい"))
    }

    @Test
    fun `a string starts after the previous terminator`() {
        val bytes = "前".toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0) + "今の行".toByteArray(Charsets.UTF_16LE)
        assertEquals(4, MemoryScan.stringStart(bytes, 8, Charsets.UTF_16LE))
        val utf8 = "ab".toByteArray() + byteArrayOf(0) + "cd".toByteArray()
        assertEquals(3, MemoryScan.stringStart(utf8, 4, Charsets.UTF_8))
        // A record header (two addresses) right before the text, with no terminator.
        val record = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0x0801969C).putInt(0x080197A0).array() +
            "次の漢字".toByteArray(Charsets.UTF_16LE)
        assertEquals(8, MemoryScan.stringStart(record, 12, Charsets.UTF_16LE))
    }

    @Test
    fun `OCR's line matches the memory string despite misreads and line breaks`() {
        val memory = "次の\u0001CRD赤い字\u0001CBKの漢字\nの読みをひらがな\nで書きなさい。"
        assertTrue(MemoryScan.matches(memory, "次の赤い字の漢字の読みをひらがなで書きなさい。"))
        // One character misread.
        assertTrue(MemoryScan.matches("データを選択してください。", "デ一タを選択してください。"))
        // Contains the characters, but is a whole page of other text.
        assertFalse(MemoryScan.matches("データ" + "あ".repeat(60) + "選択", "データ選択"))
        assertFalse(MemoryScan.matches("まったく別の文", "データを選択してください。"))
    }

    @Test
    fun `clean keeps the kanji under inline furigana`() {
        assertEquals("奇跡の仮面による奇跡の力は", MemoryScan.clean("[奇跡/きせき]の[仮面/かめん]による[奇跡/きせき]の[力/ちから]は"))
        assertEquals("[A] 押す", MemoryScan.clean("[A] 押す"))
        assertEquals("ルーク、ちょっと待ってくれ。", MemoryScan.clean("<T><M9/1/1>ルーク、ちょっと[待/ま]ってくれ。"))
    }

    @Test
    fun `clean drops control characters`() {
        assertEquals("一行目二行目", MemoryScan.clean("一行目\n二行目\u0000"))
    }

    @Test
    fun `pointers are aligned little-endian words holding the address`() {
        val chunk = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x08019712).putInt(0x30001000).putInt(7).putInt(0x30001000).array()
        assertEquals(
            listOf(0x08000004L to 0x30001000L, 0x0800000CL to 0x30001000L),
            MemoryScan.pointersInto(chunk, 0x08000000L, 0x30001000L..0x30001010L),
        )
        assertEquals(0x30001000L, MemoryScan.u32(chunk.copyOfRange(4, 8)))
    }

    @Test
    fun `a pointer through a mirror counts as one to the address it folds to`() {
        val chunk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0x80012340.toInt()).putInt(0x00012340).array()
        assertEquals(
            listOf(0L to 0x12340L, 4L to 0x12340L),
            MemoryScan.pointersInto(chunk, 0L, 0x12300L..0x12400L) { it and 0x1FFFFFFFL },
        )
    }

    @Test
    fun `big-endian memory's pointers and UTF-16 text`() {
        // A GameCube's: words and UTF-16 high byte first.
        val chunk = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(0x80550384.toInt()).putInt(0xC0123400.toInt()).array()
        assertEquals(
            listOf(0x80000000L to 0x80550384L, 0x80000004L to 0x80123400L),
            MemoryScan.pointersInto(chunk, 0x80000000L, 0x80000000L..0x817FFFFFL, bigEndian = true) { it and 0x81FFFFFFL },
        )
        assertEquals(0x80550384L, MemoryScan.u32(chunk, bigEndian = true))
        assertEquals(0xC0123400L, MemoryScan.u32(chunk, at = 4, bigEndian = true))
        val text = "前".toByteArray(Charsets.UTF_16BE) + byteArrayOf(0, 0) + "今の行".toByteArray(Charsets.UTF_16BE)
        assertEquals(4, MemoryScan.stringStart(text, 8, Charsets.UTF_16BE))
        assertEquals(listOf(MemoryScan.Page(0, "前"), MemoryScan.Page(4, "今の行")), MemoryScan.pages(text, Charsets.UTF_16BE))
    }

    @Test
    fun `UTF-16 is looked for in the memory's byte order, EUC-JP in both`() {
        assertEquals(listOf(Charsets.UTF_16LE, Charsets.UTF_8, MemoryScan.SHIFT_JIS, MemoryScan.EUC_JP), MemoryScan.charsets(bigEndian = false))
        assertEquals(Charsets.UTF_16BE, MemoryScan.charsets(bigEndian = true).first())
        assertEquals(2, MemoryScan.unit(Charsets.UTF_16BE))
        assertEquals(1, MemoryScan.unit(MemoryScan.EUC_JP))
    }

    @Test
    fun `a script splits into the pages the player sees, without markup`() {
        val script = "<E800 268><E003 60><CNTR>１２月１７日\n<CNTR>地方裁判所<E023><PAGE>\n<E800 276>" +
            "<E025 4>（うーん。<E003 34>ぜんっ<E003 20>ぜん）<E023><PAGE>\n<E800 299><E040 0 1>"
        val bytes = script.toByteArray(Charsets.UTF_8)
        val pages = MemoryScan.pages(bytes, Charsets.UTF_8)
        assertEquals(listOf("１２月１７日地方裁判所", "（うーん。ぜんっぜん）"), pages.map { it.text })
        assertEquals(0, pages[0].offset)
        // The second page starts right after the first one's page tag.
        assertEquals(script.substringBefore("\n<E800 276>").toByteArray(Charsets.UTF_8).size, pages[1].offset)
    }

    @Test
    fun `terminators end pages too, in UTF-16`() {
        val bytes = "一つ目".toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0) + "二つ目".toByteArray(Charsets.UTF_16LE)
        assertEquals(
            listOf(MemoryScan.Page(0, "一つ目"), MemoryScan.Page(8, "二つ目")),
            MemoryScan.pages(bytes, Charsets.UTF_16LE),
        )
    }

    @Test
    fun `a text box kept as one string a line is found whole, without the bytes between`() {
        val sjis = MemoryScan.SHIFT_JIS
        // Memories Off (PS1): each line is `10 xx 4B`, the text, `’` and NUL, then 16 (same box) or 17 (box ends).
        fun line(text: String, next: Int) =
            byteArrayOf(0x10, 0xBC.toByte(), 0x4B) + "$text’".toByteArray(sjis) + byteArrayOf(0, next.toByte())
        val bytes = line("ずっと昔から変わらなかった。", 0x16) + line("春のうららかな日々を経て、移りゆく季節を共に過ごす事。", 0x17) +
            line("何の疑いも無く、ありきたりな日常を経て……", 0x16)
        val ocr = "ずっと昔から変わらなかった。春のうららかな日々を経て、移りゆく季節を共に「ごす事。"
        val (page, score) = MemoryScan.bestRun(bytes, sjis, ocr)!!
        assertEquals("ずっと昔から変わらなかった。春のうららかな日々を経て、移りゆく季節を共に過ごす事。", page.text)
        assertEquals(0, page.offset)
        assertTrue(score > 0.9)
    }

    @Test
    fun `stray characters OCR didn't see come off the ends, Japanese stays`() {
        assertEquals("経て……", MemoryScan.trimStrays("ｽK経て……’", "経て……"))
        assertEquals("「過ごす」", MemoryScan.trimStrays("「過ごす」", "過ごす"))
        assertEquals("ABC", MemoryScan.trimStrays("xABCx", "ABC"))
    }

    @Test
    fun `a table of lines counts as a script, a lone line doesn't`() {
        val sjis = charset("Shift_JIS")
        val table = "<T>ルーク、ちょっと[待/ま]ってくれ。\u0000<T>[調査/ちょうさ]モード[中/ちゅう]は\u0000".toByteArray(sjis)
        assertEquals(2, MemoryScan.scriptLines(table, sjis))
        val buffer = "今帰りかい？\u0000\u0000\u0000ab\u0000".toByteArray(sjis)
        assertEquals(1, MemoryScan.scriptLines(buffer, sjis))
    }
}
