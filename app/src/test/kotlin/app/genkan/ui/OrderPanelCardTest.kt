package app.genkan.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.library.LibraryOrder
import app.genkan.library.LibrarySort
import org.junit.Assert.assertEquals
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

    private fun tap(text: String, from: LibraryOrder): LibraryOrder {
        var changed = from
        compose.setContent { GenkanTheme { OrderPanelCard(from, row = 0, onOrder = { changed = it }, onClose = {}) } }
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
        return changed
    }

    @Test
    fun `the left arrow steps back`() {
        assertEquals(LibrarySort.LAST_PLAYED, tap("‹", LibraryOrder(sort = LibrarySort.RECENTLY_ADDED)).sort)
    }

    @Test
    fun `the right arrow steps on`() {
        assertEquals(LibrarySort.MAIN_STORY, tap("›", LibraryOrder(sort = LibrarySort.RECENTLY_ADDED)).sort)
    }

    @Test
    fun `the rest of the row steps on, as A does`() {
        assertEquals(LibrarySort.LAST_PLAYED, tap("Title", LibraryOrder(sort = LibrarySort.TITLE)).sort)
    }

    @Test
    fun `the filter row toggles`() {
        assertEquals(true, tap("Downloaded only", LibraryOrder()).downloadedOnly)
    }
}
