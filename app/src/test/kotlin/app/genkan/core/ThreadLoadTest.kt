package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadLoadTest {
    @Test
    fun `a stat line gives the thread's name and its user plus system ticks`() {
        // pid (comm) state ppid ... utime(14) stime(15) ...
        val line = "25308 (GLThread 117) R 25269 25269 0 0 -1 4194368 1 0 0 0 1500 260 0 0 12 -8 31 0 9"
        assertEquals("GLThread 117" to 1760L, ThreadLoad.parseStat(line))
        // A name with parentheses of its own.
        assertEquals("a (b)" to 3L, ThreadLoad.parseStat("1 (a (b)) S 0 0 0 0 0 0 0 0 0 0 1 2 0"))
        assertNull(ThreadLoad.parseStat("garbage"))
    }

    @Test
    fun `each thread's share of one CPU since the last sample, busiest first`() {
        val load = ThreadLoad(ticksPerSecond = 100)
        val before = mapOf(1 to 100L, 2 to 50L, 3 to 10L)
        val now = mapOf(1 to ("EE" to 160L), 2 to ("GS" to 80L), 3 to ("idle" to 10L), 4 to ("new" to 5L))
        val busy = load.busiest(before, now, elapsedNanos = 1_000_000_000L)
        assertEquals(listOf("EE", "GS"), busy.map { it.name })
        assertEquals(0.6, busy[0].share, 1e-9)
        assertEquals(0.3, busy[1].share, 1e-9)
    }

    @Test
    fun `nothing the first time, and over two seconds the share halves`() {
        val load = ThreadLoad(ticksPerSecond = 100)
        assertTrue(load.busiest(emptyMap(), mapOf(1 to ("EE" to 10L)), 1_000_000_000L).isEmpty())
        assertEquals(0.5, load.busiest(mapOf(1 to 0L), mapOf(1 to ("EE" to 100L)), 2_000_000_000L).single().share, 1e-9)
    }
}
