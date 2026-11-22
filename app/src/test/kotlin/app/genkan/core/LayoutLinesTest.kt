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
    fun `a wait partway through a message sends what's shown, then the rest as the line grows`() {
        read("前半。後半。", waiting = false, shown = 1)
        val first = read("前半。後半。", waiting = true, shown = 3)
        assertEquals(listOf("前半。"), texts(first))
        assertNull(first.lines.single().takesBack)
        // Still waiting there: nothing again.
        assertEquals(emptyList<String>(), texts(read("前半。後半。", waiting = true, shown = 3)))
        read("前半。後半。", waiting = false, shown = 5)
        val whole = read("前半。後半。", waiting = true, shown = 6)
        assertEquals("前半。後半。", whole.lines.single().text)
        assertEquals("前半。", whole.lines.single().takesBack)
    }

    @Test
    fun `a message with U+FFFD or a control character is nothing shown`() {
        assertEquals(listOf("本物の行"), texts(read("本物の行", waiting = true)).also { read(null) })
        // Noise (a movie's data in the text memory) makes no line, and clears what was there.
        read("前の行", waiting = false)
        assertEquals(listOf("前の行"), texts(read("O\u001fﾔK\uFFFD", waiting = false)))
        assertEquals(emptyList<String>(), texts(read("O\u001fﾔK\uFFFD", waiting = true)))
        // Row breaks are fine.
        assertEquals(listOf("一行目二行目"), texts(read("一行目\n二行目", waiting = true)))
        assertTrue(LayoutLines.shown("一行目\n二行目"))
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

    @Test
    fun `while a choice is up, only the question goes, not the answers`() {
        val asking = LayoutRead(message = "どうする？\n：右へ行く\n：左へ行く", waiting = true, choosing = true, choices = listOf("右へ行く", "左へ行く"))
        assertEquals(listOf("どうする？"), texts(lines.read(asking)))
        // Answers alone (no question): nothing goes.
        lines.reset()
        assertEquals(emptyList<String>(), texts(lines.read(asking.copy(message = "：右へ行く\n：左へ行く"))))
        // Not choosing: the rows are the message, as before.
        lines.reset()
        assertEquals(listOf("：右へ行く：左へ行く"), texts(lines.read(asking.copy(message = "：右へ行く\n：左へ行く", choosing = false))))
    }

    @Test
    fun `an answer row is the answer, or it with a short mark before it`() {
        assertEquals("問い\n長い前置きの右へ行く", LayoutLines.withoutAnswers("問い\n：右へ行く\n長い前置きの右へ行く\n右へ行く", listOf("右へ行く")))
    }

    @Test
    fun `answers typed out before the choice is up don't go either`() {
        val answers = listOf("右へ行く", "左へ行く")
        lines.read(LayoutRead(message = "：右へ", waiting = false, choosing = false, choices = emptyList()))
        lines.read(LayoutRead(message = "：右へ行く\n：左へ行く", waiting = false, choosing = false, choices = emptyList()))
        val up = lines.read(LayoutRead(message = "：右へ行く\n：左へ行く", waiting = false, choosing = true, choices = answers))
        assertEquals(emptyList<String>(), texts(up))
        assertNull(lines.settled())
        // With a question first: the question goes, alone.
        lines.reset()
        lines.read(LayoutRead(message = "どうする？\n：右へ行く", waiting = false, choosing = false, choices = emptyList()))
        lines.read(LayoutRead(message = "どうする？\n：右へ行く\n：左へ行く", waiting = false, choosing = true, choices = answers))
        assertEquals("どうする？", lines.settled()?.text)
    }

    @Test
    fun `the choice's page left up a moment after the answer doesn't go`() {
        val answers = listOf("右へ行く", "左へ行く")
        lines.read(LayoutRead(message = "：右へ行く\n：左へ行く", waiting = false, choosing = true, choices = answers))
        // A taken: the game waits a frame with the page still there, then clears it.
        assertEquals(emptyList<String>(), texts(lines.read(LayoutRead(message = "：右へ行く\n：左へ行く", waiting = true, choosing = false, choices = emptyList()))))
        assertEquals(emptyList<String>(), texts(lines.read(LayoutRead(message = "", waiting = false, choosing = false, choices = emptyList()))))
        // The next page is a line again.
        assertEquals(listOf("はい"), texts(lines.read(LayoutRead(message = "はい", waiting = true, choosing = false, choices = emptyList()))))
    }

    @Test
    fun `a question typed out with its answers goes once, the answers never`() {
        val answers = listOf("右へ行く", "左へ行く")
        val sent = mutableListOf<String>()
        fun step(message: String, waiting: Boolean, choosing: Boolean) {
            sent += texts(lines.read(LayoutRead(message = message, waiting = waiting, choosing = choosing, choices = if (choosing) answers else emptyList())))
        }
        // Typing: the question, then the answers, all before the game says it's a choice.
        for (typed in listOf("どう", "どうする？", "どうする？\n：右へ", "どうする？\n：右へ行く\n：左へ行く")) step(typed, waiting = false, choosing = false)
        step("どうする？\n：右へ行く\n：左へ行く", waiting = false, choosing = true)
        lines.settled()?.let { sent += it.text }
        // The cursor moves (nothing changes in the message), then A: a frame waiting, then cleared.
        step("どうする？\n：右へ行く\n：左へ行く", waiting = false, choosing = true)
        step("どうする？\n：右へ行く\n：左へ行く", waiting = true, choosing = false)
        step("", waiting = false, choosing = false)
        lines.settled()?.let { sent += it.text }
        assertEquals(listOf("どうする？"), sent)
    }
}
