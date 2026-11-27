package app.genkan.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.core.ScreenLook
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The list's "show all" chip, by touch and on the pad, and the looks it keeps listed. A tall screen, so every row is laid out. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36], qualifiers = "w600dp-h3000dp")
class LookPickerToggleTest {
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

    private fun marked(label: String) = compose.onNode(hasText(label) and hasText("✓"))

    @Test
    fun `the chip shows every look and then the console's again, the look in use marked throughout`() {
        compose.setContent {
            LookPicker(
                title = "A game: look",
                selected = ScreenLook.LCD1X_PSP,
                onPick = {},
                onClose = {},
                suits = ScreenLook.suiting("psp"),
            )
        }
        compose.onAllNodesWithText("CRT Lottes").assertCountEquals(0)
        marked("LCD 1x (PSP)").assertExists()
        compose.onNodeWithText("Show all 19").performClick()
        compose.onNodeWithText("CRT Lottes").assertExists()
        marked("LCD 1x (PSP)").assertExists()
        compose.onNodeWithText("Show fewer").performClick()
        compose.onAllNodesWithText("CRT Lottes").assertCountEquals(0)
        marked("LCD 1x (PSP)").assertExists()
    }

    @Test
    fun `a look in use outside the console's stays listed after another is picked`() {
        // The reader's list: a pick draws the game with it, and the list stays open.
        var now by mutableStateOf(ScreenLook.DOT)
        compose.setContent {
            LookPicker(title = "Look for this play", selected = now, onPick = { it?.let { now = it } }, onClose = {}, suits = ScreenLook.suiting("psx"))
        }
        marked("Dot").assertExists()
        compose.onNodeWithText("CRT Lottes").performClick()
        marked("CRT Lottes").assertExists()
        compose.onNodeWithText("Dot").assertExists()
        compose.onNodeWithText("Dot").performClick()
        marked("Dot").assertExists()
    }

    @OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    fun `on the pad, the chip is one Right from the look in use, and keeps the focus after A`() {
        compose.setContent {
            // The settings page is reached on the pad, out of touch mode, where rows take focus.
            androidx.compose.ui.platform.LocalInputModeManager.current.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            ConsoleContent(platform = "psx", look = ScreenLook.CRT_LOTTES, picking = true)
        }
        compose.waitForIdle()
        marked("CRT Lottes").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Show all 19").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Show fewer").assertIsFocused()
        compose.onNodeWithText("Dot").assertExists()
    }
}
