package app.genkan.ui

import app.genkan.launch.LocalShelves
import app.genkan.library.GameCase
import app.genkan.library.GameEntity
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

    private fun game(id: Long, slug: String, platformId: Long = 2) =
        GameEntity(id, platformId, slug, "Game $id", "game $id", null, 0, null, false)
    private val jewel = GameCase.specFor("psx").aspect
    private val psp = GameCase.specFor("psp").aspect

    @Test
    fun `on a shelf of several consoles the slots fit the focused case, jewel case first`() {
        val shelf = listOf(game(1, "psx"), game(2, "psp"), game(3, "psx"))
        assertEquals(jewel, ShelfGeometry.slotAspect(shelf, 0), 0f)
        // The narrow PSP case in focus: its gaps are a PSP case's, not the jewel case's.
        assertEquals(psp, ShelfGeometry.slotAspect(shelf, 1), 0f)
    }

    @Test
    fun `on a shelf of several consoles the slots fit the focused case, narrow case first`() {
        val shelf = listOf(game(1, "psp"), game(2, "psx"), game(3, "psp"))
        assertEquals(psp, ShelfGeometry.slotAspect(shelf, 0), 0f)
        // The wide jewel case in focus gets a jewel case's room, so its neighbours don't cover it.
        assertEquals(jewel, ShelfGeometry.slotAspect(shelf, 1), 0f)
    }

    @Test
    fun `a shelf of one console keeps one slot size`() {
        val shelf = (1L..5L).map { game(it, "ps2") }
        val sizes = shelf.indices.map { ShelfGeometry.slotAspect(shelf, it) }.toSet()
        assertEquals(setOf(GameCase.specFor("ps2").aspect), sizes)
        // Apps and PC games are plain square tiles.
        val apps = listOf(game(1, LocalShelves.APPS_SLUG, LocalShelves.APPS_ID))
        assertEquals(1f, ShelfGeometry.slotAspect(apps, 0), 0f)
    }
}
