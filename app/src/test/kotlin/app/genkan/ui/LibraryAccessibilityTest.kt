package app.genkan.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.library.LibraryFocus
import app.genkan.library.LibraryPane
import app.genkan.library.LibraryView
import app.genkan.library.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library as Android's accessibility (TalkBack, uiautomator) gets it, node info by node info:
 * each game one node, clickable and titled, the focused one checked. Compose's semantics tree
 * can look right while the exported nodes split (a description handed to a node of its own).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36], qualifiers = "w832dp-h468dp-land-369dpi")
class LibraryAccessibilityTest {
    @get:Rule(order = 0)
    val activity = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
            org.robolectric.Shadows.shadowOf(context.packageManager)
                .addActivityIfNotPresent(android.content.ComponentName(context, ComponentActivity::class.java))
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(view: LibraryView, gameIndex: Int) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                GenkanTheme {
                    LibraryContent(
                        view = view,
                        platforms = FakeData.platforms,
                        games = FakeData.psxGames,
                        focus = LibraryFocus(pane = LibraryPane.GAMES, platformIndex = 1, gameIndex = gameIndex),
                        sync = SyncStatus.Done(games = 64),
                        downloads = FakeData.downloads,
                        notice = null,
                        coverModel = { game -> "fake:${game.id}" },
                        artModel = { path -> path?.let { "fake:$it" } },
                        emulatorFor = { null },
                        onSelectPlatform = {},
                        onColumns = {},
                        onSelectGame = {},
                        onToggleView = {},
                        staticMedia = true,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun composeView(): View {
        fun find(v: View): View? = if (v.javaClass.simpleName == "AndroidComposeView") v
            else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        return find(compose.activity.window.decorView)!!
    }

    /** The node info Android gets for every semantics node with an id, as uiautomator walks it. */
    private fun exported(): List<AccessibilityNodeInfo> {
        val view = composeView()
        val provider = view.accessibilityNodeProvider!!
        val ids = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().map { it.id }
        return ids.mapNotNull { provider.createAccessibilityNodeInfo(it) }
    }

    private fun check(view: LibraryView, focusedIndex: Int) {
        show(view, focusedIndex)
        val nodes = exported()
        val titles = FakeData.psxGames.map { it.name }.toSet()
        val described = nodes.filter { it.contentDescription?.toString() in titles }
        assertTrue("no game exported", described.isNotEmpty())
        // Each game one node: titled, clickable, and its title once.
        for (node in described) assertTrue("${node.contentDescription} isn't clickable", node.isClickable)
        assertEquals(described.size, described.map { it.contentDescription.toString() }.toSet().size)
        // The focused game is the one checked node, as uiautomator dumps it (checked="true").
        assertEquals(listOf(FakeData.psxGames[focusedIndex].name), described.filter { it.isChecked }.map { it.contentDescription.toString() })
    }

    @Test
    fun `on the shelf each game is one clickable titled node, the focused one checked`() = check(LibraryView.SHELF, 3)

    @Test
    fun `in the grid each game is one clickable titled node, the focused one checked`() = check(LibraryView.GRID, 2)
}
