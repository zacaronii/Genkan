package app.genkan.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualScreenReducerTest {

    private val start = DualScreenStatus(secondaryDisplayId = 2)

    @Test
    fun `the companion landing on the secondary display turns dual mode on`() {
        val status = DualScreenReducer.onLowerPlaced(start, displayId = 2)
        assertEquals(ScreenMode.DUAL, status.mode)
        assertEquals(2, status.lowerDisplayId)
        assertTrue(status.secondaryUsable)
    }

    @Test
    fun `the companion landing on the default display latches single-screen`() {
        val status = DualScreenReducer.onLowerPlaced(start, displayId = 0)
        assertEquals(ScreenMode.SINGLE, status.mode)
        assertNull(status.lowerDisplayId)
        assertFalse(status.secondaryUsable)
        assertFalse(status.companionEnabled)
    }

    @Test
    fun `the latch survives an environment refresh and clears only on reprobe`() {
        val refused = DualScreenReducer.onLowerPlaced(start, displayId = 0)
        val refreshed = DualScreenReducer.withEnvironment(
            refused, isDefaultHome = true, homeAliasEnabled = true, companionEnabled = false, secondaryDisplayId = 2
        )
        assertFalse(refreshed.secondaryUsable)
        assertTrue(DualScreenReducer.reprobe(refreshed).secondaryUsable)
    }

    @Test
    fun `losing the companion's display drops to single mode`() {
        val dual = DualScreenReducer.onLowerPlaced(start, displayId = 2)
        val refreshed = DualScreenReducer.withEnvironment(
            dual, isDefaultHome = true, homeAliasEnabled = true, companionEnabled = true, secondaryDisplayId = null
        )
        assertEquals(ScreenMode.SINGLE, refreshed.mode)
        assertNull(refreshed.lowerDisplayId)
    }

    @Test
    fun `a stale destroy for another display leaves the running companion alone`() {
        val dual = DualScreenReducer.onLowerPlaced(start, displayId = 2)
        assertEquals(dual, DualScreenReducer.onLowerGone(dual, displayId = 0))
        assertEquals(ScreenMode.SINGLE, DualScreenReducer.onLowerGone(dual, displayId = 2).mode)
    }

    @Test
    fun `releasing the bottom screen disables the companion`() {
        val dual = DualScreenReducer.onLowerPlaced(start, displayId = 2)
        val released = DualScreenReducer.onReleased(dual)
        assertEquals(ScreenMode.SINGLE, released.mode)
        assertFalse(released.companionEnabled)
    }
}
