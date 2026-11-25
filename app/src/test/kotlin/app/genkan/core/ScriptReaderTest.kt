package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A script's events (made-up text) as the reader takes them. */
class ScriptReaderTest {

    private fun text(text: String, id: String, channel: String = "dialogue", speaker: String = "", window: String? = null) =
        ScriptEvents.TEXT to """{"seq":1,"frame":1,"channel":"$channel","speaker":"$speaker","text":"$text","ruby":[],"id":"$id","meta":{}""" +
            (window?.let { ""","window":"$it"""" } ?: "") + "}"

    private fun layout(window: String, closed: Boolean = false) =
        ScriptEvents.LAYOUT to if (closed) """{"window":"$window","seq":1,"closed":true}""" else """{"window":"$window","seq":1,"space":[640,480],"glyphs":[]}"""

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
