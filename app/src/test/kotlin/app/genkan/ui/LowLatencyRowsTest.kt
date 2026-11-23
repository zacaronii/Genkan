package app.genkan.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The low-latency rows on the Screen look and sound page: below the screenshot's fold, so checked here. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class LowLatencyRowsTest {
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
    fun `each console's row says whether low latency is on, and a press switches that console`() {
        val pressed = mutableListOf<String>()
        compose.setContent {
            GenkanTheme { ScreenLookContent(emptyMap(), lowLatencyOff = setOf("psx"), onToggleLowLatency = { pressed += it }) }
        }
        compose.onNodeWithText("Game Boy: on").assertExists()
        compose.onNodeWithText("PlayStation: off").assertExists()
        compose.onNodeWithText("PlayStation: off").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("psx"), pressed)
    }

    @Test
    fun `the face-button rows say the layout and each console's swap, and a press switches them`() {
        var layout = 0
        val swapped = mutableListOf<String>()
        compose.setContent {
            GenkanTheme {
                ScreenLookContent(
                    emptyMap(), aOnRight = false, onToggleAOnRight = { layout++ },
                    faceSwapped = setOf("snes"), onToggleFaceSwap = { swapped += it },
                )
            }
        }
        compose.onNodeWithText("Face buttons: A at the bottom (Xbox layout)").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, layout)
        compose.onNodeWithText("Game Boy: as the device").assertExists()
        compose.onNodeWithText("Super Famicom: swapped").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("snes"), swapped)
    }
}
