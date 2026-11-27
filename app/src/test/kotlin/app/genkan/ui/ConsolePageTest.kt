package app.genkan.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.core.CoreCatalog
import app.genkan.core.PlayerChoice
import app.genkan.core.ScreenLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Each console's page (Settings › Consoles), and the move to it: every setting the single "Screen
 * look and sound" page had (each console's look, low-latency sound, face-button swap and player;
 * the layout and learning switches) still has its place.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ConsolePageTest {
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

    private val consoles = SettingsGroups.DEFAULT.single { it.id == "consoles" }

    @Test
    fun `every console the built-in player runs has a row, once, opening its page`() {
        // sfam is RomM's other name for the Super Famicom, which the snes row covers; famicom the
        // Famicom's, which the nes row does; and the Mega Drive's two others, which genesis does.
        val aliases = setOf("sfam", "famicom", "genesis-slash-megadrive", "megadrive")
        val rows = consoles.rows.filterIsInstance<SettingsRow.Console>()
        assertEquals(CoreCatalog.platforms - aliases, rows.map { it.platform }.toSet())
        assertEquals(rows.size, rows.map { it.platform }.toSet().size)
        rows.forEach { assertEquals(setOf(SetupAction.CONSOLE), it.actions) }
        // Each one offers looks to pick from.
        rows.forEach { assertTrue(it.platform, ScreenLook.suiting(it.platform)?.isNotEmpty() == true) }
    }

    @Test
    fun `the old page's switches for every console are rows here`() {
        val switches = SettingsGroups.DEFAULT.flatMap { it.rows }.filterIsInstance<SettingsRow.Switch>().map { it.key }
        assertTrue(SwitchKey.LEARNING in switches)
        assertTrue(SwitchKey.FACE_A_ON_RIGHT in switches)
        assertTrue(SwitchKey.AZAHAR_TEXT in switches)
    }

    @Test
    fun `a page has its look, sound and swap, each pressed to change it`() {
        var looks = 0
        var latency = 0
        var swaps = 0
        compose.setContent {
            GenkanTheme {
                ConsoleContent(
                    platform = "psx", look = ScreenLook.SHARP,
                    onOpenLooks = { looks++ },
                    lowLatency = false, onToggleLowLatency = { latency++ },
                    faceSwapped = true, onToggleFaceSwap = { swaps++ },
                )
            }
        }
        compose.onNodeWithText("PlayStation").assertExists()
        compose.onNodeWithText("Look: Sharp  ·  ${ScreenLook.SHARP.description}").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Low-latency sound: off (for sound that's missing or crackles)").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Swap A/B and X/Y: swapped").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(1, 1, 1), listOf(looks, latency, swaps))
    }

    @Test
    fun `a console with a choice of player, and one whose text has a source, have those rows too`() {
        var cycles = 0
        val actions = mutableListOf<SetupAction>()
        compose.setContent {
            GenkanTheme {
                ConsoleContent(
                    platform = "ps2", look = ScreenLook.DEFAULT,
                    playsWith = PlayerChoice.Option(CoreCatalog.EXTERNAL, "ARMSX2"), onCyclePlaysWith = { cycles++ },
                    text = ConsoleText(on = false, from = "ARMSX2's memory", action = SetupAction.ARMSX2_PINE), onAction = { actions += it },
                )
            }
        }
        compose.onNodeWithText("Plays with: ARMSX2").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Text: OCR").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, cycles)
        assertEquals(listOf(SetupAction.ARMSX2_PINE), actions)
    }

    @Test
    fun `the PSP and the PS2 read text from their emulators, the others have no text row`() {
        val state = FakeData.settings.copy(pspDebugger = true, armsxPine = false)
        assertEquals(ConsoleText(true, consoleText("psp", state)!!.from, SetupAction.PSP_DEBUGGER), consoleText("psp", state))
        assertEquals(SetupAction.ARMSX2_PINE, consoleText("ps2", state)!!.action)
        assertNull(consoleText("psx", state))
        assertEquals(SettingsGroups.ON_CONSOLE_PAGES, setOf(SetupAction.PSP_DEBUGGER, SetupAction.ARMSX2_PINE))
    }

    @Test
    fun `a console's row says its look and what differs from the usual`() {
        val state = FakeData.settings.copy(
            looks = mapOf("psx" to ScreenLook.CRT_LOTTES),
            lowLatencyOff = setOf("psx"),
            faceSwapped = setOf("psx"),
            pspDebugger = true,
        )
        assertEquals("CRT Lottes, plain sound, A/B swapped", consoleSummary("psx", state))
        assertEquals(ScreenLook.defaultFor("gba").label, consoleSummary("gba", state))
        assertTrue(consoleSummary("psp", state).endsWith("text from memory"))
        // A console played by other than its usual player says which.
        val other = PlayerChoice.options("ps2").last()
        assertTrue(consoleSummary("ps2", state.copy(coreChoice = mapOf("ps2" to other.value))).contains(other.label))
    }
}
