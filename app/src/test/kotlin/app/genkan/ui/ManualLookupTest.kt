package app.genkan.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.dictionary.LookupResult
import app.genkan.dictionary.TermHit
import app.genkan.ocr.ScreenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36], qualifiers = "w538dp-h468dp-land-369dpi")
class ManualLookupTest {
    // The host activity createComposeRule starts, registered here instead of through ui-test-manifest.
    @get:Rule(order = 0)
    val activity = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
            org.robolectric.Shadows.shadowOf(context.packageManager)
                .addActivityIfNotPresent(android.content.ComponentName(context, androidx.activity.ComponentActivity::class.java))
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    // A made-up line of four characters, boxed one by one.
    private val line = ScreenText.Line("ことばを", 0.2f, 0.3f, 0.6f, 0.35f, ends = listOf(0 to 0.3f, 1 to 0.4f, 2 to 0.5f, 3 to 0.6f))
    private val hit = ManualHit(1, ScreenText.Hit("ことばを", 1, line))
    private fun result(matched: String) = LookupResult("とばを", listOf(TermHit(matched = matched, deinflected = matched, expression = matched)), "[]")

    @Test
    fun `the box is the tapped character until the dictionaries answer, then the match`() {
        val waiting = manualMark(hit, Lookup(MANUAL_LINE, 1, null, "ことばを"))!!
        assertEquals(1, waiting.page)
        assertEquals(ScreenText.Box(0.3f, 0.3f, 0.4f, 0.35f), waiting.box)
        val answered = manualMark(hit, Lookup(MANUAL_LINE, 1, result("とば"), "ことばを"))!!
        assertEquals(ScreenText.Box(0.3f, 0.3f, 0.5f, 0.35f), answered.box)
        // Nothing found: the tapped character stays boxed.
        val none = manualMark(hit, Lookup(MANUAL_LINE, 1, LookupResult("とばを", emptyList(), "[]"), "ことばを"))!!
        assertEquals(ScreenText.Box(0.3f, 0.3f, 0.4f, 0.35f), none.box)
    }

    @Test
    fun `no box for a lookup that isn't the manual's, or without one`() {
        assertNull(manualMark(hit, Lookup(5L, 1, null, "ことばを")))
        assertNull(manualMark(hit, null))
        assertNull(manualMark(null, Lookup(MANUAL_LINE, 1, null, "ことばを")))
    }

    @Test
    fun `the panel opens on the other half from the tap`() {
        assertFalse(panelOnTop(0.1f))
        assertFalse(panelOnTop(0.49f))
        assertTrue(panelOnTop(0.5f))
        assertTrue(panelOnTop(0.95f))
        // Under half the height, so the tapped line is clear of it either way.
        assertTrue(MANUAL_PANEL_FRACTION < 0.5f)
    }

    @Test
    fun `Back closes the panel first, then the manual`() {
        var lookup by mutableStateOf<Lookup?>(Lookup(MANUAL_LINE, 1, null, "ことばを"))
        var manualClosed = 0
        compose.setContent {
            ManualWithLookup(lookup, onTop = false, onCloseLookup = { lookup = null }, popup = { Box(Modifier.fillMaxSize()) }) {
                ManualViewer(FakeManual(pages = 2), onClose = { manualClosed++ })
            }
        }
        compose.runOnIdle { BottomBack.handle() }
        compose.waitForIdle()
        assertNull(lookup)
        assertEquals(0, manualClosed)
        compose.runOnIdle { BottomBack.handle() }
        assertEquals(1, manualClosed)
    }

    /** Page 1's top-left and size on the screen, as drawn. */
    private fun pageOnScreen(): Pair<Offset, androidx.compose.ui.geometry.Size> {
        val c = compose.onNodeWithContentDescription("Page 1").fetchSemanticsNode().layoutInfo.coordinates
        val topLeft = c.localToRoot(Offset.Zero)
        val bottomRight = c.localToRoot(Offset(c.size.width.toFloat(), c.size.height.toFloat()))
        return topLeft to androidx.compose.ui.geometry.Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y)
    }

    @Test
    fun `a tap's height on screen is measured as drawn, zoomed or not`() {
        val heights = mutableListOf<Float>()
        compose.setContent {
            ManualViewer(FakeManual(pages = 2), onClose = {}, onTapText = { _, _, _, _, tapY -> heights += tapY })
        }
        val root = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
        fun tapAt(screenY: Float) {
            val (topLeft, size) = pageOnScreen()
            compose.onRoot().performTouchInput { click(Offset(topLeft.x + size.width * 0.5f, screenY)) }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        tapAt(root * 0.3f)
        tapAt(root * 0.8f)
        val (topLeft, size) = pageOnScreen()
        compose.onRoot().performTouchInput { doubleClick(Offset(topLeft.x + size.width * 0.5f, root * 0.3f)) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        tapAt(root * 0.8f)
        assertEquals(3, heights.size)
        assertEquals(0.3f, heights[0], 0.02f)
        assertEquals(0.8f, heights[1], 0.02f)
        assertEquals(0.8f, heights[2], 0.02f)
        assertFalse(panelOnTop(heights[0]))
        assertTrue(panelOnTop(heights[1]) && panelOnTop(heights[2]))
    }

    /** The tops of the nodes showing [text], in root pixels. */
    private fun topsOf(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().map { it.positionInRoot.y }

    @Test
    fun `with the panel on top, the bar's message shows below it`() {
        var onTop by mutableStateOf(true)
        compose.setContent {
            ManualWithLookup(
                Lookup(MANUAL_LINE, 1, null, "ことばを"), onTop = onTop, onCloseLookup = {},
                popup = { Box(Modifier.fillMaxSize()) }, status = ManualTaps.NO_TEXT,
            ) {
                ManualViewer(FakeManual(pages = 2), onClose = {}, status = ManualTaps.NO_TEXT)
            }
        }
        val height = compose.onRoot().fetchSemanticsNode().size.height
        // The bar's copy is under the panel; the other is clear of it.
        assertTrue(topsOf(ManualTaps.NO_TEXT).any { it >= height * MANUAL_PANEL_FRACTION })
        onTop = false
        compose.waitForIdle()
        // A panel at the bottom leaves the bar in view: only its copy.
        assertEquals(1, topsOf(ManualTaps.NO_TEXT).size)
        assertTrue(topsOf(ManualTaps.NO_TEXT).single() < height * 0.2f)
    }
}
