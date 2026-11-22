package app.genkan.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.library.LibraryOrder
import app.genkan.library.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Touch on the shelf's Sort & filter panel: each arrow of the Sort row steps its own way. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36])
class OrderPanelCardTest {
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

    /** The panel showing [order]; each tap's change is shown in turn. */
    private var order by mutableStateOf(LibraryOrder())

    private fun show(from: LibraryOrder) {
        order = from
        compose.setContent { GenkanTheme { OrderPanelCard(order, row = 0, onOrder = { order = it }, onClose = {}) } }
    }

    private fun arrow(glyph: String) = compose.onNodeWithTag("sort-arrow-$glyph")

    @Test
    fun `the left arrow steps back`() {
        show(LibraryOrder(sort = LibrarySort.RECENTLY_ADDED))
        arrow("‹").performClick()
        compose.waitForIdle()
        assertEquals(LibrarySort.LAST_PLAYED, order.sort)
    }

    @Test
    fun `the right arrow steps on`() {
        show(LibraryOrder(sort = LibrarySort.RECENTLY_ADDED))
        arrow("›").performClick()
        compose.waitForIdle()
        assertEquals(LibrarySort.MAIN_STORY, order.sort)
    }

    @Test
    fun `the rest of the row steps on, as A does`() {
        show(LibraryOrder(sort = LibrarySort.TITLE))
        // The row's left end: its name, away from both arrows.
        compose.onNodeWithText("Sort", substring = false, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(LibrarySort.LAST_PLAYED, order.sort)
    }

    @Test
    fun `the filter row toggles`() {
        show(LibraryOrder())
        compose.onNodeWithText("Downloaded only", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(true, order.downloadedOnly)
    }

    @Test
    fun `each arrow takes taps over at least 48 dp square, and near its edges`() {
        show(LibraryOrder(sort = LibrarySort.RECENTLY_ADDED))
        for (glyph in listOf("‹", "›")) {
            // The laid-out size, not the bounds the row clips it to.
            val size = arrow(glyph).fetchSemanticsNode().size
            val least = with(compose.density) { 48.dp.roundToPx() }
            assertTrue("$glyph is ${size.width}x${size.height}", size.width >= least && size.height >= least)
        }
        // 4 dp in from each side of each area, well off the glyph itself.
        for ((glyph, step) in listOf("‹" to LibrarySort.LAST_PLAYED, "›" to LibrarySort.MAIN_STORY)) {
            for (x in listOf(4f, 44f)) {
                order = LibraryOrder(sort = LibrarySort.RECENTLY_ADDED)
                compose.waitForIdle()
                arrow(glyph).performTouchInput { click(Offset(with(density) { x.dp.toPx() }, centerY)) }
                compose.waitForIdle()
                assertEquals("$glyph at $x dp", step, order.sort)
            }
        }
    }
}
