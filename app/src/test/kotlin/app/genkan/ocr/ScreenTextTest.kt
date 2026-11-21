package app.genkan.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextTest {
    // A 1000x500 frame read in a crop from (100, 300): one line 400 px wide, ten characters.
    private val screen = ScreenText.of(
        listOf(OcrLine("あいうえおかきくけこ", top = 20, left = 50, height = 40, width = 400)),
        cropLeft = 100,
        cropTop = 300,
        frameWidth = 1000,
        frameHeight = 500,
    )

    @Test
    fun `a line's box is placed in the whole frame, as fractions`() {
        val line = screen.lines.single()
        assertEquals(0.15f, line.left, 1e-6f)
        assertEquals(0.64f, line.top, 1e-6f)
        assertEquals(0.55f, line.right, 1e-6f)
        assertEquals(0.72f, line.bottom, 1e-6f)
    }

    @Test
    fun `a tap's share of the box is its share of the line`() {
        assertEquals(ScreenText.Hit("あいうえおかきくけこ", 0), screen.hit(0.151f, 0.7f))
        // 0.15 + 0.4 * 0.35: the fourth character.
        assertEquals(3, screen.hit(0.29f, 0.7f)?.offset)
        assertEquals(9, screen.hit(0.55f, 0.7f)?.offset)
        assertNull(screen.hit(0.6f, 0.7f))
        assertNull(screen.hit(0.3f, 0.5f))
    }

    @Test
    fun `vertical text is measured top to bottom`() {
        val tall = ScreenText.of(listOf(OcrLine("縦書きの行", top = 0, left = 0, height = 500, width = 50)), 0, 0, 100, 500)
        assertTrue(tall.lines.single().vertical)
        assertEquals(2, tall.hit(0.2f, 0.5f)?.offset)
    }

    @Test
    fun `boxes without a size, or blank lines, are left out`() {
        val lines = listOf(OcrLine("  ", 0, 0, 10, 10), OcrLine("文字", 0, 0, 10, 0))
        assertEquals(ScreenText.EMPTY, ScreenText.of(lines, 0, 0, 100, 100))
        assertEquals(ScreenText.EMPTY, ScreenText.of(listOf(OcrLine("文字", 0, 0, 10, 10)), 0, 0, 0, 0))
    }

    @Test
    fun `it goes to the player and back as JSON`() {
        assertEquals(screen, ScreenText.fromJson(screen.toJson()))
        assertEquals(ScreenText.EMPTY, ScreenText.fromJson(""))
    }
}
