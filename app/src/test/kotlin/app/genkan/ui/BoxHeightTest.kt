package app.genkan.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class BoxHeightTest {
    @Test
    fun `with room to spare, as tall as allowed`() {
        assertEquals(300f, boxHeight(500.dp, 300.dp, 0.7f, 0.1f).value, 0.01f)
    }

    @Test
    fun `turned any way, the box fits its slot, at the one height`() {
        val front = 0.72f
        val spine = 0.12f
        val height = boxHeight(200.dp, 300.dp, front, spine)
        assertTrue(height < 300.dp)
        for (degrees in 0 until 360 step 3) {
            val turn = Math.toRadians(degrees.toDouble())
            val shown = height.value * (front * abs(cos(turn)) + spine * abs(sin(turn))).toFloat()
            assertTrue("$degrees degrees: $shown", shown <= 200f + 0.01f)
        }
    }
}
