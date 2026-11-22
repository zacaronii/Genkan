package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameIdTest {
    @Test
    fun `the id in RAM wins over a different stored one`() {
        // Disc 2 of a game whose disc 1 was played before.
        assertEquals("SLPS-01101", GameId.choose(ram = "SLPS-01101", stored = "SLPS-01100", attempt = 0))
        assertEquals("SLPS-01101", GameId.choose(ram = "SLPS-01101", stored = "SLPS-01100", attempt = 5))
    }

    @Test
    fun `with nothing in RAM, the stored id from the second try on`() {
        assertNull(GameId.choose(ram = null, stored = "SLPS-01100", attempt = 0))
        assertEquals("SLPS-01100", GameId.choose(ram = null, stored = "SLPS-01100", attempt = 1))
    }

    @Test
    fun `nothing in RAM and nothing stored is no id`() {
        assertNull(GameId.choose(ram = null, stored = null, attempt = 3))
    }

    @Test
    fun `the id found earlier in this run stands in only from the second try, and RAM's wins over it`() {
        // The player passes this run's id (else the stored one) as the stand-in.
        val thisRun = "SLPS-01100"
        assertNull(GameId.choose(ram = null, stored = thisRun, attempt = 0))
        assertEquals(thisRun, GameId.choose(ram = null, stored = thisRun, attempt = 1))
        // Disc 2 started later in the same run.
        assertEquals("SLPS-01101", GameId.choose(ram = "SLPS-01101", stored = thisRun, attempt = 0))
    }
}
