package app.genkan.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertBeatsTest {

    @Test
    fun `a disc stays still until seated, then spins up`() {
        assertEquals(0f, InsertBeats.spin(0f), 0f)
        assertEquals(0f, InsertBeats.spin(InsertBeats.SEAT_END), 0f)
        assertEquals(360f * InsertBeats.SPIN_TURNS, InsertBeats.spin(1f), 0.001f)
        // Speeding up: the second half of the spin turns three times as far as the first.
        val mid = (InsertBeats.SEAT_END + 1f) / 2f
        assertEquals(3f, (InsertBeats.spin(1f) - InsertBeats.spin(mid)) / InsertBeats.spin(mid), 0.001f)
    }

    @Test
    fun `the click presses and releases within its beat`() {
        assertEquals(0f, InsertBeats.click(InsertBeats.SEAT_END), 0.0001f)
        assertEquals(1f, InsertBeats.click((InsertBeats.SEAT_END + InsertBeats.CLICK_END) / 2f), 0.0001f)
        assertEquals(0f, InsertBeats.click(InsertBeats.CLICK_END), 0.0001f)
        assertEquals(0f, InsertBeats.click(1f), 0.0001f)
    }

    @Test
    fun `the beats run in order and phases stay within 0 and 1`() {
        assertTrue(InsertBeats.LIFT_END < InsertBeats.SEAT_END)
        assertTrue(InsertBeats.SEAT_END < InsertBeats.CLICK_END)
        assertTrue(InsertBeats.CLICK_END < InsertBeats.DOOR_END)
        assertEquals(0f, InsertBeats.phase(-1f, 0.2f, 0.4f), 0f)
        assertEquals(0.5f, InsertBeats.phase(0.3f, 0.2f, 0.4f), 0.0001f)
        assertEquals(1f, InsertBeats.phase(2f, 0.2f, 0.4f), 0f)
    }

    @Test
    fun `a disc still spinning after the animation keeps the speed it ended at`() {
        // The spin-up ends at 2 x turns / remaining time per ms; one turn at that speed:
        val ms = InsertBeats.turnMsAtEnd(1_500)
        val endSpeedDegPerMs = 2f * 360f * InsertBeats.SPIN_TURNS / ((1f - InsertBeats.SEAT_END) * 1_500)
        assertEquals(360f / endSpeedDegPerMs, ms, 0.01f)
    }
}
