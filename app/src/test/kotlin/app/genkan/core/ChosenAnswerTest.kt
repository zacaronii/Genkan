package app.genkan.core

import app.genkan.yomikata.LayoutRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChosenAnswerTest {
    private val answers = listOf("Go to the station", "Stay home")

    private fun asking(cursor: Long?) = LayoutRead(message = "Where to?", choosing = true, choices = answers, choiceCursor = cursor)
    private val closed = LayoutRead(message = "You set off.", choosing = false)

    @Test
    fun `the row the cursor was on when the choice closed is the answer`() {
        val chosen = ChosenAnswer()
        assertNull(chosen.read(asking(-1)))
        assertNull(chosen.read(asking(0)))
        assertNull(chosen.read(asking(1)))
        assertEquals("Stay home", chosen.read(closed))
        // Once: the next reads give nothing.
        assertNull(chosen.read(closed))
    }

    @Test
    fun `no answer when the cursor's row isn't known as the choice closes`() {
        // Still at -1: no row picked (the choice closed some other way, or the game took no answer).
        val atStart = ChosenAnswer()
        atStart.read(asking(-1))
        assertNull(atStart.read(closed))
        // A layout that doesn't read the cursor.
        val noCursor = ChosenAnswer()
        noCursor.read(asking(null))
        assertNull(noCursor.read(closed))
        // A cursor past the answers.
        val past = ChosenAnswer()
        past.read(asking(5))
        assertNull(past.read(closed))
    }

    @Test
    fun `a choice the layout stopped reading gives nothing`() {
        val chosen = ChosenAnswer()
        chosen.read(asking(0))
        chosen.reset()
        assertNull(chosen.read(closed))
    }

    @Test
    fun `reads with no choice give nothing`() {
        assertNull(ChosenAnswer().read(closed))
    }

    @Test
    fun `the backlog line marks the answer`() {
        assertEquals("→ Stay home", ChosenAnswer.line("Stay home"))
    }
}
