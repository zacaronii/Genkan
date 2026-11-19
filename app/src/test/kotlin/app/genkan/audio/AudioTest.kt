package app.genkan.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTest {

    private val rate = 1000 // one sample per millisecond keeps the arithmetic readable

    @Test
    fun `the ring cuts by wall-clock time`() {
        val ring = AudioRing(rate, seconds = 10)
        val second = ShortArray(rate) { it.toShort() }
        ring.write(second, second.size, now = 5_000) // samples 0..999 cover 4000..5000 ms
        val clip = ring.clip(4_100, 4_200)
        assertEquals(100, clip.size)
        assertEquals(100.toShort(), clip.first())
    }

    @Test
    fun `the ring keeps only its last seconds`() {
        val ring = AudioRing(rate, seconds = 2)
        for (t in 1..5) ring.write(ShortArray(rate) { t.toShort() }, rate, now = t * 1_000L)
        val all = ring.clip(0, 5_000)
        assertEquals(2 * rate, all.size) // seconds 4 and 5
        assertEquals(4.toShort(), all.first())
        assertEquals(5.toShort(), all.last())
    }

    @Test
    fun `silence is trimmed off both ends, with padding`() {
        val loud = ShortArray(400) { if (it % 2 == 0) 12_000 else -12_000 }
        val clip = ShortArray(1000) + loud + ShortArray(1000)
        val trimmed = ClipTrimmer.trim(clip, rate)!!
        // 400 ms of voice plus 150 ms either side.
        assertEquals(700, trimmed.size)
        assertTrue(trimmed.any { it != 0.toShort() })
    }

    @Test
    fun `a silent clip is no clip`() {
        assertNull(ClipTrimmer.trim(ShortArray(2000), rate))
        assertNull(ClipTrimmer.trim(ShortArray(2000) { 3 }, rate))
    }

    @Test
    fun `a clip that is sound throughout stays whole`() {
        val loud = ShortArray(500) { if (it % 2 == 0) 8_000 else -8_000 }
        assertArrayEquals(loud, ClipTrimmer.trim(loud, rate))
    }
}
