package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayPanelTest {
    @Test
    fun `a panel goes to the player and back as JSON`() {
        val panel = OverlayPanel(OverlayPanel.Kind.CHOICE, listOf("Yes", "No"), highlighted = 1)
        assertEquals(panel, OverlayPanel.fromJson(panel.toJson()))
        val none = OverlayPanel(OverlayPanel.Kind.MENU, listOf("Items", "Status"))
        assertEquals(none, OverlayPanel.fromJson(none.toJson()))
    }

    @Test
    fun `blank, empty, unknown kinds and a highlight off the list read as no panel or no highlight`() {
        assertNull(OverlayPanel.fromJson(""))
        assertNull(OverlayPanel.fromJson("""{"kind":"CHOICE","items":[]}"""))
        assertNull(OverlayPanel.fromJson("""{"kind":"MAP","items":["a"]}"""))
        assertNull(OverlayPanel.fromJson("not json"))
        assertNull(OverlayPanel.fromJson("""{"kind":"CHOICE","items":["a","b"],"highlighted":5}""")!!.highlighted)
    }

    @Test
    fun `a layout's choice is a choice panel, its cursor -1 no highlight`() {
        assertEquals(
            OverlayPanel(OverlayPanel.Kind.CHOICE, listOf("Yes", "No"), highlighted = 0),
            OverlayPanel.of(BuiltInText.Choice(listOf("Yes", "No"), cursor = 0)),
        )
        assertNull(OverlayPanel.of(BuiltInText.Choice(listOf("Yes", "No"), cursor = -1))!!.highlighted)
        assertNull(OverlayPanel.of(BuiltInText.Choice(listOf("Yes", "No"), cursor = null))!!.highlighted)
        assertNull(OverlayPanel.of(BuiltInText.Choice(emptyList(), cursor = 0)))
        assertNull(OverlayPanel.of(null))
    }

    @Test
    fun `the panel sits in the top right and a tap finds its row`() {
        val g = PanelGeometry.place(rows = 3, viewWidth = 1000f, viewHeight = 600f, rowHeight = 40f, margin = 20f)
        assertEquals(1000f - 20f - 380f, g.left)
        assertEquals(20f, g.top)
        assertEquals(140f, g.bottom)
        assertEquals(0, g.rowAt(800f, 25f))
        assertEquals(2, g.rowAt(800f, 139f))
        assertNull(g.rowAt(800f, 141f))
        assertNull(g.rowAt(100f, 50f))
    }

    @Test
    fun `rows shrink to fit a short overlay`() {
        val g = PanelGeometry.place(rows = 10, viewWidth = 1000f, viewHeight = 240f, rowHeight = 40f, margin = 20f)
        assertEquals(20f, g.rowHeight)
        assertEquals(220f, g.bottom)
    }
}
