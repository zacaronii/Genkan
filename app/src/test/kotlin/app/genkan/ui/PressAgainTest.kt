package app.genkan.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PressAgainTest {

    @Test
    fun `a second press on the same thing soon after confirms it`() {
        val press = PressAgain(windowMs = 4_000)
        assertFalse(press.press("art", 0))
        assertEquals("art", press.armed(1_000))
        assertTrue(press.press("art", 3_999))
        assertNull(press.armed(4_000))
    }

    @Test
    fun `too late, or on something else, it arms again instead`() {
        val press = PressAgain(windowMs = 4_000)
        assertFalse(press.press("art", 0))
        assertFalse(press.press("art", 4_000))
        assertFalse(press.press("manuals", 5_000))
        assertTrue(press.press("manuals", 6_000))
    }
}
