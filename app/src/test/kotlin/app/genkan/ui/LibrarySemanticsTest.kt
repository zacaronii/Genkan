package app.genkan.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.library.LibraryFocus
import app.genkan.library.LibraryPane
import app.genkan.library.LibraryView
import app.genkan.library.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The focused game, found as accessibility (and device tests) see it: the one selected node, titled. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36], qualifiers = "w832dp-h468dp-land-369dpi")
class LibrarySemanticsTest {
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

    /** The titles of the selected nodes that carry a description. */
    private fun selectedTitles(): List<String?> =
        compose.onAllNodes(isSelected() and SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }

    /** The selected node's description as accessibility reads it: merged with everything under it. */
    private fun mergedDescription(): List<String>? =
        compose.onAllNodes(isSelected(), useUnmergedTree = false).fetchSemanticsNodes().single()
            .config.getOrNull(SemanticsProperties.ContentDescription)

    @Test
    fun `on the shelf, the focused case is the one selected game`() {
        show(LibraryView.SHELF, gameIndex = 3)
        assertEquals(listOf(FakeData.psxGames[3].name), selectedTitles())
        // Read once: the cover and logo under the case add nothing.
        assertEquals(listOf(FakeData.psxGames[3].name), mergedDescription())
    }

    @Test
    fun `in the grid, the focused card is the one selected game`() {
        show(LibraryView.GRID, gameIndex = 2)
        assertEquals(listOf(FakeData.psxGames[2].name), selectedTitles())
        assertEquals(listOf(FakeData.psxGames[2].name), mergedDescription())
    }
}
