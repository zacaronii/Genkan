package app.genkan.ui

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A single tap on a manual page reports where on the page it landed, zoomed or not. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36], qualifiers = "w538dp-h468dp-land-369dpi")
class ManualTapTest {
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

    private data class Tap(val page: Int, val x: Float, val y: Float, val width: Int)

    /** Lets a single tap through: it's only a tap once a second one hasn't followed. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** Page 1's top-left corner and size on the screen, as drawn (zoom included). */
    private fun pageOnScreen(): Pair<Offset, androidx.compose.ui.geometry.Size> {
        val node = compose.onNodeWithContentDescription("Page 1").fetchSemanticsNode()
        val size = node.layoutInfo.coordinates.let { c ->
            val topLeft = c.localToRoot(Offset.Zero)
            val bottomRight = c.localToRoot(Offset(c.size.width.toFloat(), c.size.height.toFloat()))
            topLeft to androidx.compose.ui.geometry.Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y)
        }
        return size
    }

    /** A tap at ([fx], [fy]) of page 1 as it's drawn now. */
    private fun tapPage(fx: Float, fy: Float) {
        val (topLeft, size) = pageOnScreen()
        compose.onRoot().performTouchInput { click(Offset(topLeft.x + size.width * fx, topLeft.y + size.height * fy)) }
        settle()
    }

    @Test
    fun `a tap gives the page and the point on it, before and after zooming in`() {
        val taps = mutableListOf<Tap>()
        compose.setContent {
            ManualViewer(FakeManual(pages = 3), onClose = {}, onTapText = { page, x, y, width -> taps += Tap(page, x, y, width) })
        }
        tapPage(0.25f, 0.3f)
        val first = taps.single()
        assertEquals(0, first.page)
        assertEquals(0.25f, first.x, 0.01f)
        assertEquals(0.3f, first.y, 0.01f)
        // Zoomed in by a double tap near the top: the same point of the page is still the same fraction.
        val (topLeft, size) = pageOnScreen()
        compose.onRoot().performTouchInput { doubleClick(Offset(topLeft.x + size.width * 0.5f, topLeft.y + size.height * 0.05f)) }
        settle()
        assertEquals("a double tap isn't a lookup", 1, taps.size)
        val zoomedWidth = pageOnScreen().second.width
        assertEquals(size.width * 2, zoomedWidth, size.width * 0.05f)
        tapPage(0.45f, 0.1f)
        val zoomed = taps.last()
        assertEquals(0.45f, zoomed.x, 0.01f)
        assertEquals(0.1f, zoomed.y, 0.01f)
        // The unzoomed width either way, for OCR.
        assertEquals(first.width, zoomed.width)
    }

    @Test
    fun `without a lookup, a double tap still zooms`() {
        compose.setContent { ManualViewer(FakeManual(pages = 3), onClose = {}) }
        val before = pageOnScreen().second.width
        val (topLeft, size) = pageOnScreen()
        compose.onRoot().performTouchInput { doubleClick(Offset(topLeft.x + size.width * 0.5f, topLeft.y + size.height * 0.05f)) }
        settle()
        assertEquals(before * 2, pageOnScreen().second.width, before * 0.05f)
    }
}
