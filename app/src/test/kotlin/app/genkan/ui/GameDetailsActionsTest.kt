package app.genkan.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.core.ScreenLook
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The details' buttons on the bottom screen as the Thor lays it out (landscape, 538 by 468 dp): with
 * a logo, a manual, the look and a download, the last ones are below the screen's edge and a scroll
 * brings them up.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36], qualifiers = "w538dp-h468dp-land-369dpi")
class GameDetailsActionsTest {
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

    @Test
    fun `the look and delete buttons scroll into view and work`() {
        var looks = 0
        var deletes = 0
        compose.setContent {
            GameDetailsContent(
                details = FakeData.fullDetails.copy(look = GameLook(null, ScreenLook.SHARP)),
                onShowReader = {},
                onLook = { looks++ },
                onDelete = { deletes++ },
            )
        }
        compose.onNodeWithText("Look: console's (Sharp)").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, looks)
        // Delete asks for a second tap.
        compose.onNodeWithText("Delete download").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Tap again to delete").performClick()
        assertEquals(1, deletes)
    }
}
