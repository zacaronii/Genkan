package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsTest {
    private val second = 1_000_000_000L

    /** A second in which the core ran [runs] frames of [ms] each, at a 60 fps core. */
    private fun sample(runs: Long, ms: Double = 10.0, steps: Long = runs) = PlayerStats.Sample(
        runs = runs, runNanos = (runs * ms * 1e6).toLong(), longestNanos = (ms * 2e6).toLong(), p95Nanos = (ms * 1.5e6).toLong(),
        steps = steps, contentFpsMilli = 60_000, elapsedNanos = second, scriptNanos = 3_000_000, ringNanos = 500_000,
    )

    @Test
    fun `a second's row says the frames run, the speed against the core's rate and the core's time`() {
        val row = PlayerStats().add(sample(48, ms = 20.0))!!
        assertEquals(48.0, row.fps, 0.001)
        assertEquals(0.8, row.speed, 0.001)
        assertEquals(20.0, row.averageMs, 0.001)
        assertEquals(30.0, row.p95Ms, 0.001)
        assertEquals(40.0, row.longestMs, 0.001)
        assertEquals(3.0, row.scriptMs, 0.001)
        assertEquals(0.5, row.ringMs, 0.001)
    }

    @Test
    fun `two steps of two runs each are two frames shown, four run`() {
        val row = PlayerStats().add(sample(runs = 120, steps = 60))!!
        assertEquals(120.0, row.fps, 0.001)
        assertEquals(60.0, row.shownFps, 0.001)
    }

    @Test
    fun `behind only after two slow seconds in a row, and no longer after a fast one`() {
        val stats = PlayerStats()
        stats.add(sample(50))
        assertFalse(stats.behind)
        stats.add(sample(55))
        assertTrue(stats.behind)
        stats.add(sample(60))
        assertFalse(stats.behind)
        // 59.94 of 60 isn't slow.
        stats.add(sample(59)); stats.add(PlayerStats.Sample(5994, 1, 1, 1, 5994, 60_000, 100 * second))
        assertFalse(stats.behind)
    }

    @Test
    fun `a period with nothing run (paused) gives no row and resets the window`() {
        val stats = PlayerStats()
        stats.add(sample(40)); stats.add(sample(40))
        assertTrue(stats.behind)
        assertNull(stats.add(sample(0, steps = 0)))
        assertFalse(stats.behind)
    }
}
