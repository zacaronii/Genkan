package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class FrameRingTest {

    /** A 2×2 shot, bottom row first as GL reads it: bottom row red, top row blue, green marking its number. */
    private fun shot(n: Int): Triple<ByteBuffer, Int, Int> {
        val red = byteArrayOf(0xFF.toByte(), n.toByte(), 0, 0xFF.toByte())
        val blue = byteArrayOf(0, n.toByte(), 0xFF.toByte(), 0xFF.toByte())
        return Triple(ByteBuffer.wrap(red + red + blue + blue), 2, 2)
    }

    @Test
    fun `a shot every interval, the newest kept, the recent ones top row first`() {
        val ring = FrameRing(capacity = 3, intervalMs = 100)
        assertTrue(ring.due(1_000))
        ring.add(shot(1), 1_000, 0)
        assertFalse(ring.due(1_050))
        assertTrue(ring.due(1_100))
        for (n in 2..5) ring.add(shot(n), 1_000L + 100 * (n - 1), 0)
        // Three kept: shots 3, 4 and 5, at 1200, 1300 and 1400.
        val all = ring.recent(10_000, 1_400)
        assertEquals(listOf(3, 4, 5), all.map { it.argb[0] shr 8 and 0xFF })
        // The last 150 ms: shots 4 and 5.
        assertEquals(2, ring.recent(150, 1_400).size)
        // GL's bottom row (red) is the frame's last row; its top row is blue.
        val frame = all.last()
        assertEquals(0xFF0005FF.toInt(), frame.argb[0])
        assertEquals(0xFFFF0500.toInt(), frame.argb[2])
        ring.clear()
        assertTrue(ring.recent(10_000, 1_400).isEmpty())
    }
}
