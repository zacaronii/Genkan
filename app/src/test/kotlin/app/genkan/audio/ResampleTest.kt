package app.genkan.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ResampleTest {

    @Test
    fun `doubling the rate puts a midpoint between samples`() {
        val out = Resample.linear(shortArrayOf(0, 100, 200), 22_050, 44_100)
        assertArrayEquals(shortArrayOf(0, 50, 100, 150, 200, 200), out)
    }

    @Test
    fun `a GBA second becomes a CD-rate second`() {
        assertEquals(44_100, Resample.linear(ShortArray(32_768), 32_768, 44_100).size)
    }

    @Test
    fun `the same rate is left alone`() {
        val input = shortArrayOf(1, 2, 3)
        assertArrayEquals(input, Resample.linear(input, 44_100, 44_100))
    }
}
