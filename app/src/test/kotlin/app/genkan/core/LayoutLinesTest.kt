package app.genkan.core

import app.genkan.yomikata.LayoutRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutLinesTest {
    private val lines = LayoutLines()

    private fun read(message: String?, waiting: Boolean? = null, shown: Long? = null, speaker: String? = null) =
        lines.read(
            LayoutRead(
                message = message,
                speakerName = speaker,
                waiting = waiting,
                shown = shown,
                onScreen = if (message != null && shown != null) message.take(shown.toInt()) else null,
            ),
        )

    private fun texts(step: LayoutLines.Step) = step.lines.map { it.text }

    @Test
    fun `a line goes when the game waits for a press, once`() {
        assertEquals(emptyList<String>(), texts(read("はい", waiting = false)))
        assertEquals(listOf("はい"), texts(read("はい", waiting = true)))
        assertEquals(emptyList<String>(), texts(read("はい", waiting = true)))
    }

    @Test
    fun `the same sentence twice in a row is two lines`() {
        read("はい", waiting = true)
        read(null)
        assertEquals(listOf("はい"), texts(read("はい", waiting = true)))
        // Without the message clearing: typing starts over, so `shown` drops.
        read("はい", shown = 2)
        assertEquals(emptyList<String>(), texts(read("はい", shown = 0)))
        assertEquals(listOf("はい"), texts(read("はい", shown = 2)))
    }

    @Test
    fun `a line the game moves past by itself still goes`() {
        read("自動で進む行", waiting = false)
        // Replaced by a line that doesn't continue it, never having waited.
        assertEquals(listOf("自動で進む行"), texts(read("次の行", waiting = false)))
        // Or the message clears.
        assertEquals(listOf("次の行"), texts(read(null)))
    }

    @Test
    fun `typing out is one line, complete when all of it shows`() {
        assertEquals(emptyList<String>(), texts(read("ボクは", shown = 1)))
        assertEquals(emptyList<String>(), texts(read("ボクは立っていた", shown = 4)))
        assertEquals(listOf("ボクは立っていた"), texts(read("ボクは立っていた", shown = 8)))
    }

    @Test
    fun `a line that grows after it went goes again, taking back the first part`() {
        assertEquals(listOf("一文目。"), texts(read("一文目。", waiting = true)))
        read("一文目。二文目。", waiting = false)
        val step = read("一文目。二文目。", waiting = true)
        assertEquals("一文目。二文目。", step.lines.single().text)
        assertEquals("一文目。", step.lines.single().takesBack)
        // Then cleared: nothing more, all of it went.
        assertEquals(emptyList<String>(), texts(read(null)))
    }

    @Test
    fun `a grown line replaced before it waited again still goes, taking back the first part`() {
        read("一文目。", waiting = true)
        read("一文目。二文目。", waiting = false)
        val step = read("次の場面", waiting = false)
        assertEquals("一文目。二文目。", step.lines.single().text)
        assertEquals("一文目。", step.lines.single().takesBack)
    }

    @Test
    fun `a line with neither flag nor count settles`() {
        val step = read("静かな行")
        assertTrue(step.settle)
        assertEquals("静かな行", lines.settled()?.text)
        assertNull(lines.settled())
    }

    @Test
    fun `the speaker read late stays with the line`() {
        read("こんにちは", waiting = false, speaker = "苗木")
        val step = read("こんにちは", waiting = true)
        assertEquals("苗木", step.lines.single().speaker)
    }
}
