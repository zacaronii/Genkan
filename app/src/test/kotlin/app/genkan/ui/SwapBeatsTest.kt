package app.genkan.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwapBeatsTest {
    @Test
    fun `the media starts seated, is lifted out, and is seated again at the end`() {
        assertEquals(1f, SwapBeats.insertProgress(0f), 0f)
        assertEquals(InsertBeats.LIFT_END, SwapBeats.insertProgress(SwapBeats.OUT_LIFTED), 1e-6f)
        assertEquals(InsertBeats.LIFT_END, SwapBeats.insertProgress(0.5f), 0f)
        assertEquals(1f, SwapBeats.insertProgress(SwapBeats.IN_DONE), 1e-6f)
        assertEquals(1f, SwapBeats.insertProgress(1f), 0f)
    }

    @Test
    fun `it goes off to the left and the next comes in from the right`() {
        assertEquals(0f, SwapBeats.sideways(0.1f), 0f)
        assertEquals(-1f, SwapBeats.sideways(SwapBeats.OUT_GONE - 1e-4f), 1e-3f)
        assertEquals(1f, SwapBeats.sideways(SwapBeats.OUT_GONE), 1e-3f)
        assertEquals(0f, SwapBeats.sideways(SwapBeats.IN_OVER), 0f)
        assertFalse(SwapBeats.incoming(SwapBeats.OUT_LIFTED))
        assertTrue(SwapBeats.incoming(SwapBeats.IN_OVER))
    }

    @Test
    fun `the drive is empty until the next one starts going in`() {
        assertEquals(1_200, SwapBeats.emptyMs(2_000L))
        assertTrue(SwapBeats.insertProgress(SwapBeats.IN_SEAT) <= InsertBeats.LIFT_END + 1e-6f)
    }
}
