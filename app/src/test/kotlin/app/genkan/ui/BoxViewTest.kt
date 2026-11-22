package app.genkan.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxViewTest {
    @Test
    fun `facing front, then turned to show the spine on the left`() {
        val flat = BoxView.of(0f)
        assertFalse(flat.back)
        assertEquals(1f, flat.faceShown, 1e-4f)
        assertEquals(0f, flat.spineShown, 1e-4f)
        val turned = BoxView.of(28f)
        assertFalse(turned.back)
        assertTrue(turned.spineOnLeft)
        assertTrue(turned.spineShown > 0.4f && turned.faceShown > 0.8f)
    }

    @Test
    fun `past 90 degrees the back faces the viewer, the spine now on the right`() {
        val back = BoxView.of(160f)
        assertTrue(back.back)
        assertEquals(-20f, back.faceTurn, 1e-4f)
        assertFalse(back.spineOnLeft)
    }

    @Test
    fun `angles wrap round`() {
        assertEquals(BoxView.of(28f), BoxView.of(388f))
        assertEquals(BoxView.of(-28f), BoxView.of(332f))
    }
}
