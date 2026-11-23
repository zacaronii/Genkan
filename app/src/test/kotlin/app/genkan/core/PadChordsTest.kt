package app.genkan.core

import android.view.KeyEvent
import app.genkan.core.PadChords.Shortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadChordsTest {
    private val chords = PadChords()
    private fun down(key: Int, repeat: Int = 0) = chords.key(key, down = true, repeat = repeat)
    private fun up(key: Int) = chords.key(key, down = false)

    @Test
    fun `Select alone reaches the game when it's let go`() {
        assertTrue(down(KeyEvent.KEYCODE_BUTTON_SELECT).consume)
        val released = up(KeyEvent.KEYCODE_BUTTON_SELECT)
        assertTrue(released.tapSelect)
    }

    @Test
    fun `Select with a shortcut's button runs it, and neither reaches the game`() {
        down(KeyEvent.KEYCODE_BUTTON_SELECT)
        val save = down(KeyEvent.KEYCODE_BUTTON_R1)
        assertTrue(save.consume)
        assertEquals(Shortcut.SAVE_STATE, save.started)
        assertTrue(up(KeyEvent.KEYCODE_BUTTON_R1).consume)
        assertFalse(up(KeyEvent.KEYCODE_BUTTON_SELECT).tapSelect)
    }

    @Test
    fun `the defaults`() {
        val expected = mapOf(
            KeyEvent.KEYCODE_BUTTON_R1 to Shortcut.SAVE_STATE,
            KeyEvent.KEYCODE_BUTTON_L1 to Shortcut.LOAD_STATE,
            KeyEvent.KEYCODE_DPAD_RIGHT to Shortcut.NEXT_SLOT,
            KeyEvent.KEYCODE_DPAD_LEFT to Shortcut.PREVIOUS_SLOT,
            KeyEvent.KEYCODE_BUTTON_R2 to Shortcut.FAST_FORWARD,
            KeyEvent.KEYCODE_BUTTON_START to Shortcut.QUIT,
        )
        for ((key, shortcut) in expected) {
            down(KeyEvent.KEYCODE_BUTTON_SELECT)
            assertEquals(shortcut, down(key).started)
            up(key)
            up(KeyEvent.KEYCODE_BUTTON_SELECT)
        }
    }

    @Test
    fun `without Select the same buttons are the game's`() {
        for (key in listOf(KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_A)) {
            assertFalse(down(key).consume)
            assertFalse(up(key).consume)
        }
    }

    @Test
    fun `a button that isn't a shortcut passes while Select is held`() {
        down(KeyEvent.KEYCODE_BUTTON_SELECT)
        assertFalse(down(KeyEvent.KEYCODE_BUTTON_A).consume)
        assertFalse(up(KeyEvent.KEYCODE_BUTTON_A).consume)
        assertTrue("Select still taps: no shortcut was used", up(KeyEvent.KEYCODE_BUTTON_SELECT).tapSelect)
    }

    @Test
    fun `fast-forward runs while held, and ends on its button's release even after Select's`() {
        down(KeyEvent.KEYCODE_BUTTON_SELECT)
        assertEquals(Shortcut.FAST_FORWARD, down(KeyEvent.KEYCODE_BUTTON_R2).started)
        assertEquals(null, down(KeyEvent.KEYCODE_BUTTON_R2, repeat = 1).started)
        up(KeyEvent.KEYCODE_BUTTON_SELECT)
        val end = up(KeyEvent.KEYCODE_BUTTON_R2)
        assertTrue(end.consume)
        assertEquals(listOf(Shortcut.FAST_FORWARD), end.ended)
    }

    @Test
    fun `fast-forward is slower on the heavier consoles`() {
        assertEquals(3, PadChords.fastForwardSpeed("snes"))
        assertEquals(3, PadChords.fastForwardSpeed("gba"))
        assertEquals(2, PadChords.fastForwardSpeed("psx"))
        assertEquals(2, PadChords.fastForwardSpeed("nds"))
        assertEquals(2, PadChords.fastForwardSpeed("psp"))
    }
}
