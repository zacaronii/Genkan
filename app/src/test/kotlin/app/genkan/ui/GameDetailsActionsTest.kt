package app.genkan.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.core.ScreenLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `a game's settings button names a renderer of its own and opens its settings`() {
        var presses = 0
        val own = app.genkan.core.SettingRow(app.genkan.core.GameSettings.GRAPHICS, "Graphics", "GL", app.genkan.core.SettingSource.GAME)
        val notOwn = own.copy(value = "Vulkan", source = app.genkan.core.SettingSource.DEFAULT)
        assertEquals("Game settings (GL)", GameDetailsFormat.settingsLabel(GameSettingsNote(listOf(own))))
        assertEquals("Game settings", GameDetailsFormat.settingsLabel(GameSettingsNote(listOf(notOwn))))
        compose.setContent {
            GameDetailsContent(
                details = FakeData.fullDetails.copy(settings = GameSettingsNote(listOf(notOwn))),
                onShowReader = {},
                onSettings = { presses++ },
            )
        }
        compose.onNodeWithText("Game settings").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, presses)
    }

    @Test
    fun `the settings sheet shows where each value comes from, and a press moves that row`() {
        val pressed = mutableListOf<String>()
        compose.setContent {
            GameSettingsSheet(
                title = "Made-up Summer: settings",
                rows = listOf(
                    app.genkan.core.SettingRow(app.genkan.core.GameSettings.GRAPHICS, "Graphics", "GL", app.genkan.core.SettingSource.GAME),
                    app.genkan.core.SettingRow("pcsx2_upscale_multiplier", "Resolution", "2×", app.genkan.core.SettingSource.CONSOLE),
                    app.genkan.core.SettingRow("pcsx2_mtvu", "VU1 on its own thread", "On", app.genkan.core.SettingSource.DEFAULT),
                    app.genkan.core.SettingRow("pcsx2_ee_cycle_rate", "EE speed", "75%", app.genkan.core.SettingSource.LEARNER),
                ),
                onPress = { pressed += it },
                onReset = {},
                onClose = {},
            )
        }
        listOf("this game", "console", "default", "learned").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Resolution").performClick()
        assertEquals(listOf("pcsx2_upscale_multiplier"), pressed)
        // Something set for this game alone: Reset is there.
        compose.onNodeWithText("Reset").assertIsDisplayed()
    }

    private fun fiveButtons() = FakeData.fullDetails.copy(look = GameLook(null, ScreenLook.SHARP))

    /** Where [label]'s button really is, top to bottom in pixels: not clipped to the column, as `boundsInRoot` is. */
    private fun span(label: String): ClosedFloatingPointRange<Float> {
        val node = compose.onNodeWithText(label).fetchSemanticsNode()
        return node.positionInRoot.y..(node.positionInRoot.y + node.size.height)
    }

    /** The column of art and buttons: the scrollable that holds Play. */
    private fun column() = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Play")))

    @Test
    fun `at rest the first three buttons are whole on screen, the rest below`() {
        compose.setContent { GameDetailsContent(details = fiveButtons(), onShowReader = {}) }
        val bottom = column().fetchSemanticsNode().boundsInRoot.bottom
        for (label in listOf("Play", "Open manual", "Add to favourites")) {
            assertTrue("$label ends at ${span(label).endInclusive}, the column at $bottom", span(label).endInclusive <= bottom)
        }
        assertTrue("Delete starts at ${span("Delete download").start}, the column ends at $bottom", span("Delete download").start >= bottom)
    }

    @Test
    fun `a button given focus is brought into view`() {
        compose.setContent {
            // The pad's way of reaching it: keyboard input mode, so the buttons take focus.
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            androidx.compose.ui.platform.LocalInputModeManager.current.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            GameDetailsContent(details = fiveButtons(), onShowReader = {})
        }
        compose.onNodeWithText("Delete download").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val bottom = column().fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Delete ends at ${span("Delete download").endInclusive}, the column at $bottom", span("Delete download").endInclusive <= bottom + 0.5f)
    }

    @Test
    fun `a manual being fetched, or failing to, shows on its button, whole on screen at rest`() {
        var fetch by androidx.compose.runtime.mutableStateOf<app.genkan.manual.ManualFetch?>(app.genkan.manual.ManualFetch.Busy(0.42f))
        compose.setContent { GameDetailsContent(details = fiveButtons(), onShowReader = {}, manualFetch = fetch) }
        val bottom = column().fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("busy: ends at ${span("Fetching… 42%").endInclusive}, column at $bottom", span("Fetching… 42%").endInclusive <= bottom)
        fetch = app.genkan.manual.ManualFetch.Failed("Couldn't fetch the manual")
        compose.waitForIdle()
        assertTrue(span("Couldn't fetch the manual").endInclusive <= bottom)
    }

    @Test
    fun `without a length, "Fetching the manual…" fits its button`() {
        compose.setContent {
            GameDetailsContent(details = fiveButtons(), onShowReader = {}, manualFetch = app.genkan.manual.ManualFetch.Busy(null))
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Fetching the manual…", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("cut short", !layouts.single().hasVisualOverflow)
    }
}
