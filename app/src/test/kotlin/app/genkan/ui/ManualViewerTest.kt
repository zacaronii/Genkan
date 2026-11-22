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
}
