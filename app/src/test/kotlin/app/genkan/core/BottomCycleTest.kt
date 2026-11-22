package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomCycleTest {
    private fun cycle(from: BottomCycle, ds: Boolean, hasManual: Boolean, steps: Int) =
        generateSequence(from) { BottomCycle.next(it, ds, hasManual) }.take(steps + 1).toList()

    @Test
    fun `a DS with a manual cycles its screen, the reader and the manual`() {
        assertEquals(
            listOf(BottomCycle.GAME, BottomCycle.READER, BottomCycle.MANUAL, BottomCycle.GAME),
            cycle(BottomCycle.GAME, ds = true, hasManual = true, steps = 3),
        )
    }

    @Test
    fun `a DS without one swaps its screen and the reader, as before`() {
        assertEquals(
            listOf(BottomCycle.GAME, BottomCycle.READER, BottomCycle.GAME),
            cycle(BottomCycle.GAME, ds = true, hasManual = false, steps = 2),
        )
    }

    @Test
    fun `any other game swaps the reader and the manual`() {
        assertEquals(
            listOf(BottomCycle.READER, BottomCycle.MANUAL, BottomCycle.READER),
            cycle(BottomCycle.READER, ds = false, hasManual = true, steps = 2),
        )
    }
}
