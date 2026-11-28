package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatCeilingTest {
    private fun HeatCeiling.feed(c: Int?, seconds: Int) = (1..seconds).count { second(c) }

    @Test
    fun `ten seconds at 85 °C is said, once`() {
        val heat = HeatCeiling()
        assertEquals(0, heat.feed(85, 9))
        assertTrue(heat.second(85))
        assertEquals(0, heat.feed(90, 120))
    }

    @Test
    fun `spikes, and readings under the limit, are not`() {
        val heat = HeatCeiling()
        assertEquals(0, (1..100).count { heat.second(if (it % 3 == 0) 84 else 86) })
        assertEquals(0, heat.feed(84, 300))
        assertEquals(0, heat.feed(null, 30))
    }

    @Test
    fun `it's said again only after a minute under the limit and ten more seconds at it`() {
        val heat = HeatCeiling()
        assertEquals(1, heat.feed(86, 10))
        assertEquals(0, heat.feed(80, 30))
        assertEquals(0, heat.feed(86, 10))
        assertEquals(0, heat.feed(80, 60))
        assertFalse(heat.second(86))
        assertEquals(1, heat.feed(86, 9))
    }
}
