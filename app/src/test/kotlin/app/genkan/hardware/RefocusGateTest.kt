package app.genkan.hardware

import org.junit.Assert.assertEquals
import org.junit.Test

class RefocusGateTest {

    @Test
    fun `the first launch runs at once`() {
        assertEquals(0, RefocusGate().delayFor(now = 1_000))
    }

    @Test
    fun `launches are spaced at least 400 ms apart`() {
        val gate = RefocusGate()
        gate.markLaunched(now = 1_000)
        assertEquals(400, gate.delayFor(now = 1_000))
        assertEquals(150, gate.delayFor(now = 1_250))
        assertEquals(0, gate.delayFor(now = 1_400))
        assertEquals(0, gate.delayFor(now = 5_000))
    }
}
