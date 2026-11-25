package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A script's events (made-up text) as the reader takes them. */
class ScriptReaderTest {

    private fun text(text: String, id: String, channel: String = "dialogue", speaker: String = "", window: String? = null, seq: Int = 1) =
        ScriptEvents.TEXT to """{"seq":$seq,"frame":1,"channel":"$channel","speaker":"$speaker","text":"$text","ruby":[],"id":"$id","meta":{}""" +
            (window?.let { ""","window":"$it"""" } ?: "") + "}"

    private fun layout(window: String, closed: Boolean = false, seq: Int = 1, glyphs: String = "[]") =
        ScriptEvents.LAYOUT to if (closed) """{"window":"$window","seq":$seq,"closed":true}""" else """{"window":"$window","seq":$seq,"space":[608,480],"glyphs":$glyphs}"""

    private fun cursor(index: Int) = ScriptEvents.CURSOR to """{"index":$index,"horizontal":false}"""
    private val cursorClosed = ScriptEvents.CURSOR to """{"closed":true}"""
    private fun log(level: String, message: String) = ScriptEvents.LOG to """{"level":"$level","message":"$message"}"""

    @Test
    fun `dialogue goes to the backlog whole, with its speaker and the script's id for it`() {
        val reader = ScriptReader()
        val step = reader.read(listOf(text("一行目\\n二行目", "a1", speaker = "話し手")), 0)
        val line = step.lines.single()
        assertEquals("一行目\n二行目", line.text)
        assertTrue(line.whole)
        assertEquals("話し手", line.speaker)
        // The same id again is a reread to the reading stats: the id stands for the text.
        assertEquals("script:a1", line.sourceId)
        assertEquals("a1", reader.lastTextId)
        // An empty text gives nothing.
        assertTrue(reader.read(listOf(text("", "a2")), 0).lines.isEmpty())
    }

    @Test
    fun `the game's own furigana come with the line`() {
        val event = ScriptEvents.TEXT to """{"seq":1,"frame":1,"channel":"dialogue","speaker":"","text":"古い港","ruby":[[0,1,"ふる"],[2,1,"みなと"]],"id":"r1","meta":{}}"""
        val line = ScriptReader().read(listOf(event), 0).lines.single()
        assertEquals(listOf(app.genkan.reader.Ruby(0, 1, "ふる"), app.genkan.reader.Ruby(2, 3, "みなと")), line.ruby)
    }

    @Test
    fun `interface text and unknown channels never reach the backlog`() {
        val reader = ScriptReader()
        val step = reader.read(listOf(text("どうぐ", "m1", channel = "interface", window = "menu"), text("何か", "x1", channel = "narration")), 0)
        assertTrue(step.lines.isEmpty())
        assertNull(step.choice)
    }

    @Test
    fun `a choice is the choice on screen, its cursor the game's, and the answer taken goes in when it closes`() {
        val reader = ScriptReader()
        var step = reader.read(listOf(text("はい\\nいいえ", "c1", channel = "choice", window = "w1")), 0)
        assertTrue(step.lines.isEmpty())
        assertEquals(listOf("はい", "いいえ"), step.choice?.answers)
        step = reader.read(listOf(cursor(1)), 10)
        assertEquals(1, step.choice?.cursor)
        step = reader.read(listOf(cursorClosed), 20)
        assertEquals(listOf("→ いいえ"), step.lines.map { it.text })
        assertNull(step.choice)
    }

    @Test
    fun `a choice closes with its window, or a new text in it, and without a cursor gives no answer`() {
        val reader = ScriptReader()
        reader.read(listOf(text("はい\\nいいえ", "c1", channel = "choice", window = "w1"), cursor(0)), 0)
        assertEquals(listOf("→ はい"), reader.read(listOf(layout("w1", closed = true)), 1).lines.map { it.text })
        reader.read(listOf(text("はい\\nいいえ", "c2", channel = "choice", window = "w1"), cursor(1)), 2)
        val step = reader.read(listOf(text("次の行", "d1", window = "w1")), 3)
        assertEquals(listOf("→ いいえ", "次の行"), step.lines.map { it.text })
        reader.read(listOf(text("はい\\nいいえ", "c3", channel = "choice", window = "w2"), cursor(-1)), 4)
        assertTrue(reader.read(listOf(cursorClosed), 5).lines.isEmpty())
    }

    @Test
    fun `OCR keeps out of the backlog while a script window is up, and a little after its last text`() {
        val reader = ScriptReader()
        assertFalse(reader.windowOpen(0))
        reader.read(listOf(text("台詞", "d1", window = "w1"), layout("w1")), 1_000)
        assertTrue(reader.windowOpen(10_000))
        reader.read(listOf(layout("w1", closed = true)), 10_000)
        assertFalse(reader.windowOpen(10_000))
        // Text without a window: quiet for a few seconds after it.
        reader.read(listOf(text("台詞", "d2")), 20_000)
        assertTrue(reader.windowOpen(20_000 + ScriptReader.QUIET_MS - 1))
        assertFalse(reader.windowOpen(20_000 + ScriptReader.QUIET_MS))
    }

    @Test
    fun `a savestate load closes every window and the choice`() {
        val reader = ScriptReader()
        reader.read(listOf(text("はい\\nいいえ", "c1", channel = "choice", window = "w1"), layout("w1")), 0)
        reader.stateLoaded()
        assertNull(reader.choice)
        assertFalse(reader.windowOpen(ScriptReader.QUIET_MS))
    }

    @Test
    fun `a layout is kept for its window's text, interface text's too, until the window closes or shows another`() {
        val reader = ScriptReader()
        var step = reader.read(listOf(text("あい", "d1", window = "w1", seq = 1), layout("w1", seq = 1, glyphs = "[[10,20,16,16],[26,20,16,16]]")), 0)
        assertTrue(step.layoutsChanged)
        val layout = reader.layouts.single()
        assertEquals("あい", layout.text)
        assertEquals(608f, layout.width)
        assertEquals(26f, layout.glyphs[1]!![0])
        // Interface text: drawn and looked up on the picture, never a backlog line.
        step = reader.read(listOf(text("どうぐ", "m1", channel = "interface", window = "menu", seq = 2), layout("menu", seq = 2, glyphs = "[[0,0,8,8],[8,0,8,8],[16,0,8,8]]")), 1)
        assertTrue(step.lines.isEmpty())
        assertEquals(listOf("w1", "menu"), reader.layouts.map { it.window })
        // A layout for an older text of the window is dropped.
        reader.read(listOf(text("うえ", "d2", window = "w1", seq = 3), layout("w1", seq = 1, glyphs = "[[0,0,8,8],[8,0,8,8]]")), 2)
        assertEquals(listOf("menu"), reader.layouts.map { it.window })
        assertTrue(reader.read(listOf(layout("menu", closed = true, seq = 2)), 3).layoutsChanged)
        assertTrue(reader.layouts.isEmpty())
        reader.read(listOf(text("あい", "d3", window = "w1", seq = 4), layout("w1", seq = 4, glyphs = "[[0,0,8,8],[8,0,8,8]]")), 4)
        reader.stateLoaded()
        assertTrue(reader.layouts.isEmpty())
    }

    @Test
    fun `the script is dead once every frame callback is switched off`() {
        val reader = ScriptReader()
        assertFalse(reader.read(listOf(log("info", "script x.lua loaded for X: 2 frame callback(s)")), 0).dead)
        assertFalse(reader.read(listOf(log("error", "on_frame callback disabled after 5 failures in a row: boom")), 1).dead)
        assertTrue(reader.read(listOf(log("error", "on_frame callback disabled after 5 failures in a row: boom")), 2).dead)
        // One callback (the default): its switching off is the end.
        assertTrue(ScriptReader().read(listOf(log("error", "on_frame callback disabled after 5 failures in a row")), 0).dead)
        assertFalse(ScriptReader().read(listOf(log("warning", "something odd")), 0).dead)
    }
}
