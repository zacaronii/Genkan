package app.genkan.ui

import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadState
import app.genkan.launch.LocalShelves
import app.genkan.library.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** The hint rows say only what the focused thing can really do. */
class HintsTest {
    private val game = GameEntity(1, 2, "psx", "Example Quest", "example quest", "e.chd", 10, null, false)
    private fun download(state: DownloadState) = DownloadEntity(1, game.name, "psx", state, path = "/x", queuedAt = 0)
    private val emulator: (String) -> String? = { "DuckStation" }
    private val none: (String) -> String? = { null }

    private fun List<ButtonHint>.text() = joinToString(" | ") { h -> h.buttons.joinToString("/") { it.label } + " " + h.action }

    @Test
    fun a_game_that_would_download_never_says_play() {
        assertEquals("A Download | R2 Favourite | B Back", Hints.library(game, null, onGames = true, emulatorFor = emulator).text())
        assertEquals(
            "A Play in DuckStation | X Delete | R2 Favourite | B Back",
            Hints.library(game, download(DownloadState.INSTALLED), onGames = true, emulatorFor = emulator).text(),
        )
        assertEquals("A Cancel | R2 Favourite | B Back", Hints.library(game, download(DownloadState.RUNNING), onGames = true, emulatorFor = emulator).text())
        assertEquals("A Retry | R2 Favourite | B Back", Hints.library(game, download(DownloadState.FAILED), onGames = true, emulatorFor = emulator).text())
    }

    @Test
    fun without_an_emulator_a_has_no_hint_but_delete_stays() {
        assertEquals("X Delete | R2 Favourite | B Back", Hints.library(game, download(DownloadState.INSTALLED), onGames = true, emulatorFor = none).text())
    }

    @Test
    fun on_the_console_strip_a_chooses() {
        assertEquals("A Choose", Hints.library(game, download(DownloadState.INSTALLED), onGames = false, emulatorFor = emulator).text())
        assertEquals("", Hints.library(null, null, onGames = false, emulatorFor = emulator).text())
    }

    @Test
    fun sync_and_settings_work_anywhere_in_the_library() {
        assertEquals("L2 Sort | Y Sync | Start Settings", Hints.libraryAnywhere().text())
        // After a failed sync both stay: retry, or fix the server in settings.
        assertEquals("L2 Sort | Y Retry | Start Settings", Hints.libraryAnywhere(failed = true).text())
    }

    @Test
    fun r2_marks_or_unmarks_a_favourite() {
        assertEquals(
            "A Download | R2 Unfavourite | B Back",
            Hints.library(game, null, onGames = true, emulatorFor = emulator, favourite = true).text(),
        )
    }

    @Test
    fun local_games_open_and_have_nothing_to_delete() {
        val app = game.copy(platformId = LocalShelves.APPS_ID, platformSlug = LocalShelves.APPS_SLUG)
        assertEquals("A Open | B Back", Hints.library(app, null, onGames = true, emulatorFor = emulator).text())
    }

    @Test
    fun settings_hints_follow_the_pane_and_the_row() {
        assertEquals("A Open | B/Start Library", Hints.settings(onRows = false, rowAction = null).text())
        assertEquals("A Turn on | B Groups | Start Library", Hints.settings(onRows = true, rowAction = Hints.settingsRow("Built-in player", false)).text())
        assertEquals("A Sync library now | B Groups | Start Library", Hints.settings(onRows = true, rowAction = Hints.settingsRow("Sync library now", null)).text())
    }

    @Test
    fun dictionary_hints_follow_the_focused_row() {
        assertEquals("A Import | B Back", Hints.dictionaries(onImport = true, enabled = false, removalArmed = false).text())
        assertEquals(
            "A Turn off | L1/R1 Move up / down | X Remove | B Back",
            Hints.dictionaries(onImport = false, enabled = true, removalArmed = false).text(),
        )
        assertEquals(
            "A Turn on | L1/R1 Move up / down | X Remove (press again) | B Back",
            Hints.dictionaries(onImport = false, enabled = false, removalArmed = true).text(),
        )
    }
}
