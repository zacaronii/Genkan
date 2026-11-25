package app.genkan.armsx2

import app.genkan.armsx2.PineTicks.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class PineTicksTest {

    @Test
    fun `frames count the time the game runs, sixty a second`() {
        val ticks = PineTicks(startMs = 1_000)
        assertEquals(Step.FRAME, ticks.step(PineStatus.RUNNING, 1_100))
        assertEquals(6L, ticks.frame)
        ticks.step(PineStatus.RUNNING, 2_000)
        assertEquals(60L, ticks.frame)
    }

    @Test
    fun `a pause doesn't count, and its end may be a state loaded`() {
        val ticks = PineTicks(startMs = 0)
        ticks.step(PineStatus.RUNNING, 1_000)
        assertEquals(Step.WAIT, ticks.step(PineStatus.PAUSED, 1_100))
        assertEquals(Step.WAIT, ticks.step(PineStatus.PAUSED, 30_000))
        // The script hears of a possible load first, then frames go on from where they were.
        assertEquals(Step.STATE_LOADED, ticks.step(PineStatus.RUNNING, 30_100))
        assertEquals(60L, ticks.frame)
        assertEquals(Step.FRAME, ticks.step(PineStatus.RUNNING, 30_200))
        assertEquals(66L, ticks.frame)
    }

    @Test
    fun `no game, or no answer, stops the script`() {
        assertEquals(Step.STOP, PineTicks(0).step(PineStatus.SHUTDOWN, 100))
        assertEquals(Step.STOP, PineTicks(0).step(null, 100))
    }
}
