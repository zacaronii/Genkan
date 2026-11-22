package app.genkan.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhenGateTest {
    @Test
    fun `the first read after the layout's when turns true waits for the next`() {
        val gate = WhenGate()
        assertTrue(gate.admit(false))
        assertFalse(gate.admit(true))
        assertTrue(gate.admit(true))
        assertTrue(gate.admit(true))
        assertTrue(gate.admit(false))
        assertFalse(gate.admit(true))
    }

    @Test
    fun `a layout without a when, or a watch started mid-game, takes every read`() {
        val none = WhenGate()
        repeat(3) { assertTrue(none.admit(null)) }
        val started = WhenGate()
        assertTrue(started.admit(true))
        started.reset()
        assertTrue(started.admit(true))
    }
}
