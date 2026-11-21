package app.genkan.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfGeometryTest {

    @Test
    fun `the focused case is straight, full size and stepped forward`() {
        assertEquals(0f, ShelfGeometry.offset(0f), 0f)
        assertEquals(0f, ShelfGeometry.rotationY(0f), 0f)
        assertEquals(1f, ShelfGeometry.scale(0f), 0f)
        assertEquals(1f, ShelfGeometry.forward(0f), 0f)
        assertEquals(0f, ShelfGeometry.shade(0f), 0f)
    }

    @Test
    fun `neighbours are smaller, turned toward the middle and mirrored`() {
        assertEquals(ShelfDefaults.NEIGHBOUR_SCALE, ShelfGeometry.scale(1f), 0.0001f)
        assertEquals(ShelfDefaults.NEIGHBOUR_SCALE, ShelfGeometry.scale(-4f), 0.0001f)
        assertEquals(-ShelfGeometry.rotationY(2f), ShelfGeometry.rotationY(-2f), 0f)
        assertEquals(-ShelfGeometry.offset(3f), ShelfGeometry.offset(-3f), 0f)
        assertEquals(0f, ShelfGeometry.forward(1f), 0f)
    }

    @Test
    fun `cases further out stack closer together, and move smoothly mid-flip`() {
        val first = ShelfGeometry.offset(1f)
        val second = ShelfGeometry.offset(2f)
        val third = ShelfGeometry.offset(3f)
        assertEquals(ShelfDefaults.SIDE_OFFSET, first, 0.0001f)
        assertTrue(second - first < first)
        assertEquals(second - first, third - second, 0.0001f)
        // Halfway through a move the case is halfway to its neighbour's place.
        assertEquals(first / 2f, ShelfGeometry.offset(0.5f), 0.0001f)
    }

    @Test
    fun `only a window of cases is drawn, fading at its edges`() {
        assertEquals(0..ShelfDefaults.WINDOW, ShelfGeometry.window(0, 100))
        assertEquals(50 - ShelfDefaults.WINDOW..50 + ShelfDefaults.WINDOW, ShelfGeometry.window(50, 100))
        assertEquals(0..2, ShelfGeometry.window(1, 3))
        assertTrue(ShelfGeometry.window(0, 0).isEmpty())
        assertEquals(1f, ShelfGeometry.alpha(ShelfDefaults.FADE_FROM), 0f)
        assertEquals(0f, ShelfGeometry.alpha(ShelfDefaults.WINDOW.toFloat()), 0f)
    }

    @Test
    fun `while a direction is held, only the near cases are drawn`() {
        val reach = ShelfDefaults.HOLD_REACH
        assertEquals(1f, ShelfGeometry.alpha(-(reach - 2f), reach), 0f)
        assertEquals(0.5f, ShelfGeometry.alpha(reach - 1f, reach), 0.0001f)
        assertEquals(0f, ShelfGeometry.alpha(reach, reach), 0f)
        assertEquals(0f, ShelfGeometry.alpha(-6f, reach), 0f)
        // At rest the same cases are fully drawn.
        assertEquals(1f, ShelfGeometry.alpha(reach - 1f), 0f)
    }
}
