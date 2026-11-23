package app.genkan.core

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceButtonsTest {

    @Test
    fun `A at the bottom swaps the four face buttons into the core's positions, as libretrodroid did on its own`() {
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, FaceButtons.map(KeyEvent.KEYCODE_BUTTON_A, aOnRight = false))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, FaceButtons.map(KeyEvent.KEYCODE_BUTTON_B, aOnRight = false))
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, FaceButtons.map(KeyEvent.KEYCODE_BUTTON_X, aOnRight = false))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, FaceButtons.map(KeyEvent.KEYCODE_BUTTON_Y, aOnRight = false))
    }

    @Test
    fun `A on the right passes the four face buttons as they are`() {
        listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y).forEach {
            assertEquals(it, FaceButtons.map(it, aOnRight = true))
        }
    }

    @Test
    fun `nothing else changes in either layout`() {
        val others = listOf(
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        others.forEach {
            assertEquals(it, FaceButtons.map(it, aOnRight = false))
            assertEquals(it, FaceButtons.map(it, aOnRight = true))
        }
    }

    @Test
    fun `a console's swap turns the device's layout over`() {
        assertFalse(FaceButtons.aOnRight(deviceAOnRight = false, swapped = false))
        assertTrue(FaceButtons.aOnRight(deviceAOnRight = false, swapped = true))
        assertTrue(FaceButtons.aOnRight(deviceAOnRight = true, swapped = false))
        assertFalse(FaceButtons.aOnRight(deviceAOnRight = true, swapped = true))
    }
}
