package app.genkan.core

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackPressTest {
    private var asked = 0

    private fun press(closes: Boolean) = BackPress { asked++; closes }

    @Test
    fun `a first press that closed something takes its repeats and release, then lets go`() {
        val back = press(closes = true)
        assertTrue(back.onKey(ACTION_DOWN, 0, fallback = false))
        assertTrue(back.onKey(ACTION_DOWN, 1, fallback = false))
        assertTrue(back.onKey(ACTION_DOWN, 2, fallback = false))
        assertTrue(back.onKey(ACTION_UP, 0, fallback = false))
        assertEquals(1, asked)
        // The next press asks again.
        assertTrue(back.onKey(ACTION_DOWN, 0, fallback = false))
        assertEquals(2, asked)
    }

    @Test
    fun `a first press with nothing to close passes through, so Back quits`() {
        val back = press(closes = false)
        assertFalse(back.onKey(ACTION_DOWN, 0, fallback = false))
        assertFalse(back.onKey(ACTION_DOWN, 1, fallback = false))
        assertFalse(back.onKey(ACTION_UP, 0, fallback = false))
        assertEquals(1, asked)
    }

    @Test
    fun `a fallback Back never asks the launcher`() {
        val back = press(closes = true)
        assertFalse(back.onKey(ACTION_DOWN, 0, fallback = true))
        assertFalse(back.onKey(ACTION_UP, 0, fallback = true))
        assertEquals(0, asked)
    }

    @Test
    fun `a repeat never asks the launcher`() {
        val back = press(closes = true)
        assertFalse(back.onKey(ACTION_DOWN, 3, fallback = false))
        assertEquals(0, asked)
    }
}
