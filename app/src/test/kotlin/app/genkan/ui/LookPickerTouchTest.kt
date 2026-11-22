package app.genkan.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.core.ScreenLook
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Touch on the list of looks over another screen: its own taps work, and none goes through to what's under it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36])
class LookPickerTouchTest {
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
    fun `a tap on the list's title stays with the list, and a row's tap picks`() {
        var underneath = 0
        var picked: ScreenLook? = null
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                // What the list covers: the details' buttons, or the reader's bar during play.
                Box(Modifier.fillMaxSize().clickable { underneath++ })
                LookPicker(title = "A game: look", selected = ScreenLook.SHARP, onPick = { picked = it }, onClose = {})
            }
        }
        compose.onNodeWithText("A game: look").performClick()
        assertEquals(0, underneath)
        compose.onNodeWithText("Default").performClick()
        assertEquals(ScreenLook.DEFAULT, picked)
        assertEquals(0, underneath)
    }
}
