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

    /** Text drawn 10 px a character, so widths and breaks are easy to follow. */
    private val measure: (String) -> Float = { it.length * 10f }
    private val fit: (String, Int, Float) -> Int = { text, from, width -> minOf(text.length - from, (width / 10f).toInt()) }

    private fun place(items: List<String>, viewHeight: Float = 600f) =
        PanelGeometry.place(items, viewWidth = 1000f, viewHeight = viewHeight, lineHeight = 40f, margin = 20f, pad = 10f, measure = measure, fit = fit)

    @Test
    fun `a short list keeps the narrowest width, in the top right`() {
        val g = place(listOf("Yes", "No"))
        assertEquals(380f, g.width)
        assertEquals(1000f - 20f - 380f, g.left)
        assertEquals(20f, g.top)
        assertEquals(listOf(listOf(0), listOf(0)), g.rows.map { it.starts })
        assertEquals(0 to 0, g.lineAt(800f, 25f))
        assertEquals(1 to 0, g.lineAt(800f, 99f))
        assertNull(g.lineAt(800f, 101f))
        assertNull(g.lineAt(100f, 50f))
    }

    @Test
    fun `a long row widens the panel to fit it`() {
        val g = place(listOf("Yes", "a".repeat(45)))
        assertEquals(470f, g.width)
        assertEquals(listOf(0), g.rows[1].starts)
    }

    @Test
    fun `a row longer than the widest panel wraps`() {
        val g = place(listOf("Yes", "b".repeat(80)))
        assertEquals(600f, g.width)
        // 580 px inside the padding: 58 characters a line.
        assertEquals(listOf(0, 58), g.rows[1].starts)
        assertEquals(3, g.lines)
        assertEquals(20f + 3 * 40f, g.bottom)
    }

    @Test
    fun `a tap on a row's second line finds the character there`() {
        val text = "c".repeat(80)
        val g = place(listOf("Yes", text))
        // The second row's second line is the panel's third: y from 100 to 140.
        val (row, line) = g.lineAt(g.left + 10f + 55f, 120f)!!
        assertEquals(1 to 1, row to line)
        assertEquals(58 + 5, g.offsetAt(text, row, line, g.left + 10f + 55f, fit))
        // Past the line's end: its last character.
        assertEquals(79, g.offsetAt(text, row, line, g.left + 590f, fit))
    }

    @Test
    fun `lines shrink to fit a short overlay`() {
        val g = place(List(10) { "Row $it" }, viewHeight = 240f)
        assertEquals(20f, g.lineHeight)
        assertEquals(220f, g.bottom)
    }
}
