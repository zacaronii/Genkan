package app.genkan.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTest {

    private fun box(grey: Int) = IntArray(FrameLatch.COLUMNS * FrameLatch.ROWS) { grey }

    @Test
    fun `the latch reads once the box has changed and held still`() {
        val latch = FrameLatch(settleMs = 450)
        assertFalse(latch.onFrame(box(10), 0)) // first frame is a change
        assertFalse(latch.onFrame(box(10), 200)) // still, but not long enough
        assertTrue(latch.onFrame(box(10), 500)) // still for 450 ms: read
        assertFalse(latch.onFrame(box(10), 800)) // read already
    }

    @Test
    fun `text still being written keeps the latch waiting`() {
        val latch = FrameLatch(settleMs = 450)
        latch.onFrame(box(10), 0)
        assertFalse(latch.onFrame(box(40), 300))
        assertFalse(latch.onFrame(box(70), 600))
        assertFalse(latch.onFrame(box(70), 900))
        assertTrue(latch.onFrame(box(70), 1100))
    }

    @Test
    fun `one new character is a change`() {
        val latch = FrameLatch(settleMs = 450, threshold = 12)
        latch.onFrame(box(10), 0)
        assertTrue(latch.onFrame(box(10), 500))
        val typed = box(10).also { it[57] = 90 } // one cell of the grid got a character
        assertFalse(latch.onFrame(typed, 600))
        assertTrue(latch.onFrame(typed, 1100))
    }

    @Test
    fun `small flicker is not a change`() {
        val latch = FrameLatch(settleMs = 450, threshold = 12)
        latch.onFrame(box(10), 0)
        assertTrue(latch.onFrame(box(10), 500))
        assertFalse(latch.onFrame(box(12), 600)) // within the threshold: still the same text
        assertFalse(latch.onFrame(box(12), 1200))
    }

    @Test
    fun `signature averages each cell`() {
        val width = FrameLatch.COLUMNS * 4
        val height = FrameLatch.ROWS * 4
        val white = IntArray(width * height) { 0xFFFFFFFF.toInt() }
        assertTrue(FrameLatch.signature(white, width, height).all { it == 255 })
        val black = IntArray(width * height) { 0xFF000000.toInt() }
        assertTrue(FrameLatch.signature(black, width, height).all { it == 0 })
    }

    @Test
    fun `lines join in reading order`() {
        val lines = listOf(
            OcrLine("窓の外を眺めた。", top = 60, left = 10, height = 30),
            OcrLine("彼女はそう言って、", top = 10, left = 10, height = 30),
        )
        assertEquals("彼女はそう言って、窓の外を眺めた。", OcrText.join(lines))
    }

    @Test
    fun `pieces of one row join left to right`() {
        val lines = listOf(
            OcrLine("いい天気", top = 12, left = 200, height = 30),
            OcrLine("今日も", top = 10, left = 20, height = 30),
        )
        assertEquals("今日もいい天気", OcrText.join(lines))
    }

    @Test
    fun `an area drawn backwards is put right, a tiny one refused`() {
        val drawn = OcrRegion("g", left = 0.9f, top = 0.95f, right = 0.1f, bottom = 0.6f).cleaned()
        assertEquals(OcrRegion("g", 0.1f, 0.6f, 0.9f, 0.95f), drawn)
        assertNull(OcrRegion("g", 0.5f, 0.5f, 0.51f, 0.9f).cleaned())
        assertEquals(1f, OcrRegion("g", 0.5f, 0.5f, 1.4f, 0.9f).cleaned()?.right)
    }
}
