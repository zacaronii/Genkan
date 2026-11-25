package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A script's layouts (made-up text and cells) as the overlay's boxes. */
class ScriptScreenTest {

    private fun layout(text: String, vararg glyphs: FloatArray?, screen: Int = 0, channel: String = "dialogue") =
        ScriptReader.WindowLayout("w", text, channel, 608f, 480f, glyphs.toList(), screen)

    private fun cell(x: Float, y: Float) = floatArrayOf(x, y, 16f, 24f)

    /** A 4:3 picture on a 16:9 view: bars of an eighth on each side. */
    private val bounds = floatArrayOf(0.125f, 0f, 0.875f, 1f)

    @Test
    fun `cells go onto the game's picture, without the bars`() {
        val screen = ScriptScreen.of(listOf(layout("あい", cell(0f, 0f), cell(16f, 0f))), bounds)
        val line = screen.lines.single()
        assertEquals(0.125f, line.left, 1e-6f)
        assertEquals(0.125f + 32f / 608f * 0.75f, line.right, 1e-6f)
        assertEquals(0f, line.top, 1e-6f)
        assertEquals(24f / 480f, line.bottom, 1e-6f)
        assertEquals(listOf(0, 1), line.ends.map { it.first })
    }

    @Test
    fun `rows break where nothing is drawn and where the pen goes down a line, and a tap reads through the whole text`() {
        // 一行目 / 二行目, with a line break between (no cell) and a second row lower down.
        val screen = ScriptScreen.of(
            listOf(layout("あい\nうえ", cell(0f, 0f), cell(16f, 0f), null, cell(0f, 30f), cell(16f, 30f))),
            floatArrayOf(0f, 0f, 1f, 1f),
        )
        assertEquals(2, screen.lines.size)
        val second = screen.lines[1]
        // Each row keeps the whole text: its characters' offsets are in it.
        assertEquals("あい\nうえ", second.text)
        assertEquals(listOf(3, 4), second.ends.map { it.first })
        val hit = screen.hit(20f / 608f, 40f / 480f)!!
        assertEquals("あい\nうえ", hit.text)
        assertEquals(4, hit.offset)
        // Without a null cell, a row also ends where the pen goes back to the left.
        assertEquals(2, ScriptScreen.of(listOf(layout("あいう", cell(0f, 0f), cell(16f, 0f), cell(0f, 30f))), bounds).lines.size)
    }

    @Test
    fun `a character outside the basic plane takes two offsets, and a DS's lower screen isn't drawn here`() {
        val line = ScriptScreen.of(listOf(layout("𠮷野", cell(0f, 0f), cell(16f, 0f))), bounds).lines.single()
        assertEquals(listOf(0, 2), line.ends.map { it.first })
        assertTrue(ScriptScreen.of(listOf(layout("あ", cell(0f, 0f), screen = 1)), bounds).lines.isEmpty())
    }
}
