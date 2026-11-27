package app.genkan.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `the speed row says whether learning is on, and a press switches it`() {
        var presses = 0
        compose.setContent { GenkanTheme { ScreenLookContent(emptyMap(), learning = true, onToggleLearning = { presses++ }) } }
        compose.onNodeWithText("Learn each game's speed: on").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, presses)
    }

    @Test
    fun `the item-6 consoles have their rows too, a press switching the one pressed`() {
        val latency = mutableListOf<String>()
        val swapped = mutableListOf<String>()
        compose.setContent {
            GenkanTheme {
                ScreenLookContent(
                    emptyMap(), lowLatencyOff = setOf("dc"), onToggleLowLatency = { latency += it },
                    faceSwapped = setOf("ps2"), onToggleFaceSwap = { swapped += it },
                )
            }
        }
        compose.onNodeWithText("Dreamcast: off").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("dc"), latency)
        compose.onNodeWithText("PlayStation 2: swapped").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("ps2"), swapped)
        compose.onNodeWithText("Wii: as the device").assertExists()
        compose.onNodeWithText("PC Engine: Sharp  ·  ${app.genkan.core.ScreenLook.SHARP.description}").assertExists()
    }

    @Test
    fun `every console the built-in player runs has a row, once`() {
        // sfam is RomM's other name for the Super Famicom, which the snes row covers; famicom the
        // Famicom's, which the nes row does.
        val aliases = setOf("sfam", "famicom")
        assertEquals(app.genkan.core.CoreCatalog.platforms - aliases, LOOK_CONSOLES.map { it.first }.toSet())
        assertEquals(LOOK_CONSOLES.size, LOOK_CONSOLES.map { it.first }.toSet().size)
        // Each one offers looks to pick from.
        LOOK_CONSOLES.forEach { (platform, _) -> assertTrue(platform, app.genkan.core.ScreenLook.suiting(platform)?.isNotEmpty() == true) }
    }
}
