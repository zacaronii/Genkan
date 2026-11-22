package app.genkan.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualViewerTest {

    @Test
    fun `no extra room at 1x, then what zooming pushes below the screen`() {
        assertEquals(0f, zoomedEndPadding(1240, 1f), 0.01f)
        // At 2x only the top half is on screen, so half the height more is needed.
        assertEquals(620f, zoomedEndPadding(1240, 2f), 0.01f)
        assertEquals(930f, zoomedEndPadding(1240, 4f), 0.01f)
    }

    @Test
    fun `a zoom under 1x never takes room away`() {
        assertEquals(0f, zoomedEndPadding(1240, 0.5f), 0.01f)
    }

    @Test
    fun `a pinch keeps the point under the fingers where it is`() {
        val centreX = 540f
        // A page point at layout x, shown at centreX + (x - centreX) * zoom + shift.
        fun screenX(x: Float, zoom: Float, shift: Float) = centreX + (x - centreX) * zoom + shift
        val focusX = 800f
        val zoom = 1f
        val shift = 0f
        val under = centreX + (focusX - centreX - shift) / zoom
        val next = 4f
        val shifted = PinchFocus.shift(shift, zoom, next, focusX, centreX)
        assertEquals(focusX, screenX(under, next, shifted), 0.01f)

        // Up and down: scaled from the top, the list shows layout y at (y - scrolled) * zoom.
        val focusY = 620f
        val scrolled = 300f
        val layoutY = scrolled + focusY / 2f
        val more = PinchFocus.scroll(2f, 3f, focusY)
        assertEquals(focusY, (layoutY - (scrolled + more)) * 3f, 0.01f)
    }
}
