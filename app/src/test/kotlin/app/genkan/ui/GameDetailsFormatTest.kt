package app.genkan.ui

import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadState
import app.genkan.launch.LocalShelves
import app.genkan.library.GameEntity
import app.genkan.play.PlayTotals
import app.genkan.saves.SaveStatus
import app.genkan.stats.ReadingGameTotals
import app.genkan.core.ScreenLook
import app.genkan.manual.Manuals
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class GameDetailsFormatTest {

    private lateinit var locale: Locale
    private lateinit var zone: TimeZone

    @Before
    fun fixLocale() {
        locale = Locale.getDefault()
        zone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(locale)
        TimeZone.setDefault(zone)
    }

    private fun game(platformId: Long = 3, slug: String = "psp") = GameEntity(
        id = 1, platformId = platformId, platformSlug = slug, name = "A Game", sortTitle = "a game",
        fsName = null, fsSizeBytes = 0, coverPath = null, hasMultipleFiles = false,
    )

    @Test
    fun `durations in minutes, then hours and minutes`() {
        assertText("0 min", GameDetailsFormat.duration(0))
        assertText("0 min", GameDetailsFormat.duration(-5_000))
        assertText("59 min", GameDetailsFormat.duration(59 * 60_000L + 59_000))
        assertText("1 h 0 min", GameDetailsFormat.duration(3_600_000L))
        assertText("5 h 42 min", GameDetailsFormat.duration(5 * 3_600_000L + 42 * 60_000L))
    }

    @Test
    fun `the detail line has every part when all are known`() {
        val full = game().copy(
            releaseDateMillis = 1_247_443_200_000L, // 2009-07-13
            releaseYear = 2011,
            developer = "Example Soft",
            publishers = "Sample Publishing\nOther House",
            genres = "Adventure, Visual novel",
            playerCount = "1-2",
            regions = "Japan\nUSA",
        )
        assertText(
            "2009  ·  Example Soft  ·  Sample Publishing, Other House  ·  Adventure, Visual novel  ·  1-2 players  ·  Japan/USA",
            GameDetailsFormat.detailLine(full),
        )
    }

    @Test
    fun `the detail line skips what is empty, never leaving a dash`() {
        assertNull(GameDetailsFormat.detailLine(game()))
        assertNull(GameDetailsFormat.detailLine(game().copy(developer = "  ", genres = "", publishers = "\n", playerCount = " ")))
        assertText("2011  ·  RPG", GameDetailsFormat.detailLine(game().copy(releaseYear = 2011, genres = "RPG")))
    }

    @Test
    fun `a developer that published its own game is named once`() {
        val selfPublished = game().copy(developer = "Example Soft", publishers = "example soft\nSample Publishing")
        assertText("Example Soft  ·  Sample Publishing", GameDetailsFormat.detailLine(selfPublished))
    }

    @Test
    fun `player counts`() {
        assertText("1 player", GameDetailsFormat.players("1"))
        assertText("4 players", GameDetailsFormat.players("4"))
        assertText("1-4 players", GameDetailsFormat.players("1-4"))
        assertText("Single player", GameDetailsFormat.players("Single player"))
        assertNull(GameDetailsFormat.players(" "))
        assertNull(GameDetailsFormat.players(null))
    }

    @Test
    fun `the HowLongToBeat line rounds to hours, with no second number`() {
        assertText("Main story about 32 h", GameDetailsFormat.hltbLine(32 * 3600 + 10 * 60))
        assertText("Main story about 33 h", GameDetailsFormat.hltbLine(32 * 3600 + 30 * 60))
        assertText("Main story about 45 min", GameDetailsFormat.hltbLine(45 * 60))
        assertNull(GameDetailsFormat.hltbLine(null))
        assertNull(GameDetailsFormat.hltbLine(0))
        // The longer times, under it: either alone, or none.
        assertText("Main + extras about 45 h  ·  everything about 80 h", GameDetailsFormat.hltbMoreLine(45 * 3600, 80 * 3600))
        assertText("Main + extras about 45 h", GameDetailsFormat.hltbMoreLine(45 * 3600, null))
        assertText("Everything about 30 min", GameDetailsFormat.hltbMoreLine(0, 30 * 60))
        assertNull(GameDetailsFormat.hltbMoreLine(null, 0))
    }

    @Test
    fun `HowLongToBeat progress is the time played over the main story, capped at full`() {
        assertEquals(0.25f, GameDetailsFormat.hltbProgress(8 * 3600, 2 * 3_600_000L)!!, 0.0001f)
        assertEquals(1f, GameDetailsFormat.hltbProgress(8 * 3600, 20 * 3_600_000L)!!, 0.0001f)
        assertNull(GameDetailsFormat.hltbProgress(8 * 3600, 0))
        assertNull(GameDetailsFormat.hltbProgress(null, 3_600_000L))
        assertNull(GameDetailsFormat.hltbProgress(0, 3_600_000L))
    }

    @Test
    fun `play time with the last date, falling back to RomM's`() {
        val day = 1_768_478_400_000L // 2026-01-15
        assertText("Played 2 h 0 min  ·  last Jan 15, 2026", GameDetailsFormat.playLine(PlayTotals(3, 7_200_000L, day), null))
        assertText("Last played Jan 15, 2026", GameDetailsFormat.playLine(null, day))
        assertText("Last played Jan 15, 2026", GameDetailsFormat.playLine(PlayTotals(0, 0, null), day))
        assertNull(GameDetailsFormat.playLine(null, null))
    }

    @Test
    fun `RomM's own data, only what is set`() {
        assertNull(GameDetailsFormat.rommLine(game()))
        assertText(
            "RomM: Unfinished  ·  40% complete  ·  rated 8/10  ·  backlogged",
            GameDetailsFormat.rommLine(game().copy(userStatus = "incomplete", userCompletion = 40, userRating = 8, backlogged = true)),
        )
        assertText("RomM: Completed 100%", GameDetailsFormat.rommLine(game().copy(userStatus = "completed_100")))
        assertText("Some new state", GameDetailsFormat.statusLabel("some_new_state"))
        assertNull(GameDetailsFormat.statusLabel(" "))
    }

    @Test
    fun `reading only once something was read`() {
        assertNull(GameDetailsFormat.readingLine(ReadingGameTotals(60_000, 0)))
        assertText(
            "Read 48,213 characters (1,204 lines) in 3 h 10 min",
            GameDetailsFormat.readingLine(ReadingGameTotals(3 * 3_600_000L + 10 * 60_000L, 48_213, 1_204)),
        )
        assertText("Read 900 characters in 12 min", GameDetailsFormat.readingLine(ReadingGameTotals(12 * 60_000L, 900)))
    }

    @Test
    fun `download state lines`() {
        val sized = game().copy(fsSizeBytes = 374L shl 20)
        assertText("Not downloaded  ·  374 MB", GameDetailsFormat.downloadLine(sized, null))
        assertNull(GameDetailsFormat.downloadLine(game(platformId = -1, slug = LocalShelves.PC_SLUG), null))
        assertText("Downloaded  ·  374 MB", GameDetailsFormat.downloadLine(sized, download(DownloadState.INSTALLED)))
        // Under a megabyte, in kilobytes (a 58 KB Famicom disk is not "0 MB").
        assertText("Not downloaded  ·  58 KB", GameDetailsFormat.downloadLine(sized.copy(fsSizeBytes = 58L * 1024), null))
        assertText("Downloading… 25%", GameDetailsFormat.downloadLine(sized, download(DownloadState.RUNNING, done = 25, total = 100)))
        assertText("Download failed: timed out", GameDetailsFormat.downloadLine(sized, download(DownloadState.FAILED, error = "timed out")))
    }

    @Test
    fun `the main button says what it would do`() {
        assertText("Download", GameDetailsFormat.primaryLabel(game(), null))
        assertText("Play", GameDetailsFormat.primaryLabel(game(), download(DownloadState.INSTALLED)))
        assertText("Cancel download", GameDetailsFormat.primaryLabel(game(), download(DownloadState.RUNNING)))
        assertText("Retry download", GameDetailsFormat.primaryLabel(game(), download(DownloadState.FAILED)))
        assertText("Play", GameDetailsFormat.primaryLabel(game(platformId = -1, slug = LocalShelves.PC_SLUG), null))
        assertText("Open", GameDetailsFormat.primaryLabel(game(platformId = -2, slug = LocalShelves.APPS_SLUG), null))
    }

    @Test
    fun `save lines`() {
        assertNull(GameDetailsFormat.saveLine(null))
        assertText("Save: in step with RomM (uploaded, 9:00)", GameDetailsFormat.saveLine(SaveStatus.Synced(0, "uploaded")) { "9:00" })
        assertText("Save sync failed: offline", GameDetailsFormat.saveLine(SaveStatus.Failed(0, "offline")))
    }

    @Test
    fun `a cached manual's name changes with the manual`() {
        val first = game().copy(manualPath = "/assets/romm/resources/roms/7/1/manual/1.pdf")
        val name = Manuals.fileName(first)
        assertTrue(name, name.startsWith("1-") && name.endsWith(".pdf"))
        assertEquals(name, Manuals.fileName(first.copy(name = "Renamed")))
        assertNotEquals(name, Manuals.fileName(first.copy(manualPath = first.manualPath + "?ts=2026-11-21")))
        assertNotEquals(name, Manuals.fileName(first.copy(fsSizeBytes = 42)))
    }

    @Test
    fun `manual pages are drawn at the asked width, within the pixel cap`() {
        // A US letter page (612 x 792 points) at the bottom screen's width.
        assertEquals(1080 to 1397, PdfManualPages.renderSize(612, 792, 1080))
        // A long poster page is scaled down as a whole to stay under the cap.
        val (w, h) = PdfManualPages.renderSize(600, 12_000, 2160)
        assertTrue(w.toLong() * h <= PdfManualPages.MAX_PAGE_PIXELS)
        assertEquals(20.0, h.toDouble() / w, 0.05)
        // Degenerate sizes never give an empty bitmap.
        assertEquals(1 to 1, PdfManualPages.renderSize(0, 0, 0))
    }

    /** Compares with no-break spaces read as plain ones, so the expected text stays legible. */
    private fun assertText(expected: String, actual: String?) =
        assertEquals(expected, actual?.replace(GameDetailsFormat.NBSP, ' '))

    @Test
    fun `durations and separators never break inside`() {
        assertEquals("5 h 42 min", GameDetailsFormat.duration(5 * 3_600_000L + 42 * 60_000L))
        assertEquals("2011  ·  RPG", GameDetailsFormat.detailLine(game().copy(releaseYear = 2011, genres = "RPG")))
    }

    @Test
    fun `the look button names the game's own look, or its console's`() {
        assertEquals("Look: console's (Default)", GameDetailsFormat.lookLabel(GameLook(null, ScreenLook.DEFAULT)))
        assertEquals("Look: console's (Sharp)", GameDetailsFormat.lookLabel(GameLook(null, ScreenLook.SHARP)))
        assertEquals("Look: CRT", GameDetailsFormat.lookLabel(GameLook(ScreenLook.CRT, ScreenLook.SHARP)))
    }

    private fun download(state: DownloadState, done: Long = 0, total: Long = 0, error: String? = null) = DownloadEntity(
        romId = 1, name = "A Game", platformSlug = "psp", state = state, bytesDone = done, bytesTotal = total,
        path = "/games/a.iso", error = error, queuedAt = 0,
    )
}
