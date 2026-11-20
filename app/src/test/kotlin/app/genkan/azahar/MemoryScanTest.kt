package app.genkan.azahar

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
        assertTrue(MemoryScan.inRegions(0x30001000L))
        assertFalse(MemoryScan.inRegions(0x00100000L))
    }
}
