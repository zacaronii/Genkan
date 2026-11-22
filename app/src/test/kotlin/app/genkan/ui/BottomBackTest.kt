package app.genkan.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomBackTest {
    @Test
    fun `back closes the newest viewer first, then the one under it, then passes`() {
        val closed = mutableListOf<String>()
        val manual: () -> Unit = { closed += "manual" }
        val screenshot: () -> Unit = { closed += "screenshot" }
        BottomBack.add(manual)
        BottomBack.add(screenshot)
        assertTrue(BottomBack.handle())
        BottomBack.remove(screenshot)
        assertTrue(BottomBack.handle())
        BottomBack.remove(manual)
        assertFalse(BottomBack.active)
        assertFalse(BottomBack.handle())
        assertEquals(listOf("screenshot", "manual"), closed)
    }
}
