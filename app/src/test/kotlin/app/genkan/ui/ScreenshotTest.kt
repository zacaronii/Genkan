package app.genkan.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import app.genkan.dictionary.DictionaryEntity
import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadState
import app.genkan.home.DualScreenStatus
import app.genkan.home.ScreenMode
import app.genkan.library.FocusedGame
import app.genkan.library.GameEntity
import app.genkan.library.LibraryFocus
import app.genkan.library.LibraryPane
import app.genkan.library.LibraryView
import app.genkan.library.PlatformEntity
import app.genkan.library.SyncStatus
import app.genkan.play.PlayTotals
import app.genkan.privileged.Privileged
import app.genkan.romm.RomMAccount
import app.genkan.romm.RomMState
import app.genkan.saves.SaveStatus
import app.genkan.stats.ReadingGameTotals
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDisplay
import java.util.Locale
import java.util.TimeZone

/**
 * Screenshots of the main screens at the Thor's sizes, from made-up data. Record them with
 * `./gradlew :app:recordRoborazziDebug`; the PNGs land in app/src/test/screenshots.
 *
 * Both screens are drawn at the Thor's 369 dpi (about 2.31 px per dp): the top screen is
 * 832x468 dp and the bottom one 468x538 dp. Those round down a pixel or two, so [shoot] sets
 * the display to the real 1920x1080 and 1080x1240 px. The plain Application stands in for Genkan's Hilt one, which these
 * stateless composables don't need.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36])
class ScreenshotTest {

    @Before
    fun fixClock() {
        // The details screen formats dates and times; keep them the same on every machine.
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** The cover grid, the second view. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun libraryGrid() = shoot("library_grid", TOP_PIXELS) {
        library(LibraryView.GRID, platformIndex = 2, FakeData.games, gameIndex = 2)
    }

    /** PlayStation jewel cases, with real spines on most and the download states on the neighbours. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun shelfPlayStation() = shoot("shelf_ps1", TOP_PIXELS) {
        library(LibraryView.SHELF, platformIndex = 1, FakeData.psxGames, gameIndex = 3, staticMedia = false)
    }

    /** DS cases, narrower and rounder. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun shelfDs() = shoot("shelf_ds", TOP_PIXELS) {
        library(LibraryView.SHELF, platformIndex = 3, FakeData.dsGames, gameIndex = 4, staticMedia = false)
    }

    /** The focused PlayStation game at rest, its disc (the server's cut-out) slid out of the case. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun shelfDiscOut() = shoot("shelf_media_disc", TOP_PIXELS) {
        library(LibraryView.SHELF, platformIndex = 1, FakeData.psxGames, gameIndex = 3, staticMedia = true)
    }

    /** A DS game with no cut-out on the server: a drawn card with the logo as its label. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun shelfCardOut() = shoot("shelf_media_card", TOP_PIXELS) {
        library(LibraryView.SHELF, platformIndex = 3, FakeData.dsGames, gameIndex = 2, staticMedia = true)
    }

    /** A game without a cover: a blank case in the console's colour with the title logo. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun shelfNoCover() = shoot("shelf_no_cover", TOP_PIXELS) {
        library(LibraryView.SHELF, platformIndex = 1, FakeData.psxGames, gameIndex = 5, staticMedia = true)
    }

    /** The library on [platforms][FakeData.platforms] index [platformIndex], showing [games]. */
    @Composable
    private fun library(view: LibraryView, platformIndex: Int, games: List<GameEntity>, gameIndex: Int, staticMedia: Boolean? = null) {
        LibraryContent(
            view = view,
            platforms = FakeData.platforms,
            games = games,
            focus = LibraryFocus(pane = LibraryPane.GAMES, platformIndex = platformIndex, gameIndex = gameIndex),
            sync = SyncStatus.Done(games = 64),
            downloads = FakeData.downloads,
            notice = null,
            coverModel = { game -> game.coverPath?.let { "fake:$it" } },
            artModel = { path -> path?.let { "fake:$it" } },
            emulatorFor = { slug -> mapOf("psx" to "DuckStation", "nds" to "melonDS", "psp" to "PPSSPP")[slug] },
            onSelectPlatform = {},
            onColumns = {},
            onSelectGame = {},
            onToggleView = {},
            staticMedia = staticMedia,
        )
    }

    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun gameDetails() = shoot("game_details", BOTTOM_PIXELS) {
        val game = FakeData.games[2]
        GameDetailsContent(
            details = GameDetails(
                focus = FocusedGame.Focus(game, "PlayStation Portable", "fake-cover:${game.id}"),
                play = PlayTotals(plays = 7, totalMs = 5 * 3_600_000L + 42 * 60_000L, lastPlayed = FakeData.NOW - 86_400_000L),
                reading = ReadingGameTotals(activeMs = 3 * 3_600_000L + 10 * 60_000L, characters = 48_213),
                download = FakeData.downloads[game.id],
                save = SaveStatus.Synced(at = FakeData.NOW - 3_600_000L, what = "uploaded"),
            ),
            onShowReader = {},
        )
    }

    /** The first group with the group list focused; the others with the pad on their rows. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun settingsLibrary() = shootSettings(0, SettingsFocus(pane = SettingsPane.GROUPS, group = 0))

    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun settingsPlaying() = shootSettings(1, SettingsFocus(pane = SettingsPane.ROWS, group = 1, row = 1))

    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun settingsReading() = shootSettings(2, SettingsFocus(pane = SettingsPane.ROWS, group = 2, row = 0))

    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun settingsWords() = shootSettings(3, SettingsFocus(pane = SettingsPane.ROWS, group = 3, row = 0))

    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun settingsAnki() = shootSettings(4, SettingsFocus(pane = SettingsPane.ROWS, group = 4, row = 1))

    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun settingsDevice() = shootSettings(5, SettingsFocus(pane = SettingsPane.ROWS, group = 5, row = 0))

    /** Records group [index] of the settings page as settings_<id>.png. */
    private fun shootSettings(index: Int, focus: SettingsFocus) {
        val groups = SettingsGroups.DEFAULT
        shoot("settings_${groups[index].id}", TOP_PIXELS) {
            SetupContent(FakeData.settings, groups, focus, onSelectGroup = {}, onRow = { _, _, _ -> })
        }
    }

    /**
     * Renders [content] in Genkan's theme on a display of [pixels] (width to height) and records
     * it as [name].png. Covers come from [FakeCovers] through Coil's preview handler, which only
     * runs in inspection mode.
     */
    @OptIn(ExperimentalCoilApi::class)
    private fun shoot(name: String, pixels: Pair<Int, Int>, content: @Composable () -> Unit) {
        val display = ShadowDisplay.getDefaultDisplay()
        shadowOf(display).setRealWidth(pixels.first)
        shadowOf(display).setWidth(pixels.first)
        shadowOf(display).setRealHeight(pixels.second)
        shadowOf(display).setHeight(pixels.second)
        captureRoboImage("src/test/screenshots/$name.png") {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { request ->
                    FakeCovers.forModel(request.data.toString()).asImage()
                },
            ) {
                GenkanTheme(content)
            }
        }
    }

    private companion object {
        const val TOP_SCREEN = "w832dp-h468dp-land-369dpi"
        const val BOTTOM_SCREEN = "w468dp-h538dp-port-369dpi"
        val TOP_PIXELS = 1920 to 1080
        val BOTTOM_PIXELS = 1080 to 1240
    }
}

/** Made-up library, downloads and dictionaries; no real hosts, users or games. */
private object FakeData {
    /** A fixed "now", 2026-01-15 12:00 UTC, so dates in the screenshots never change. */
    const val NOW = 1_768_478_400_000L

    val platforms = listOf(
        PlatformEntity(1, "gba", "gba", "Game Boy Advance", 12, null),
        PlatformEntity(2, "psx", "psx", "PlayStation", 8, null),
        PlatformEntity(3, "psp", "psp", "PlayStation Portable", 14, null),
        PlatformEntity(4, "nds", "nds", "Nintendo DS", 9, null),
        PlatformEntity(-1, "genkan-pc", null, "PC (GameNative)", 3, null),
        PlatformEntity(-2, "genkan-apps", null, "Apps", 5, null),
    )

    private val titles = listOf(
        "Hoshizora Library", "Summer Lantern Festival", "The Clockmaker's Apprentice", "Lighthouse at Dusk",
        "Paper Crane Detective", "Moonlit Tea House", "Ninth Street Arcade", "Snow Country Express",
        "A Letter to Tomorrow", "Kitsune Post Office", "Rainy Season Diary", "Starfall Academy",
        "Castle of Glass Bells", "Harbor Town Mysteries",
    )

    val games = titles.mapIndexed { index, title ->
        GameEntity(
            id = 100L + index,
            platformId = 3,
            platformSlug = "psp",
            name = title,
            sortTitle = title.lowercase(),
            fsName = "$title.iso",
            fsSizeBytes = (180L + index * 97L) shl 20,
            // Two games without art show the title-only card.
            coverPath = if (index == 5 || index == 11) null else "/covers/$index.png",
            hasMultipleFiles = false,
            summary = if (index == 2) {
                "In a town where every clock has stopped, a young apprentice sets out to wind them again, " +
                    "one tower at a time. A slow, wordy mystery told over seven chapters."
            } else {
                null
            },
            genres = if (index == 2) "Adventure, Visual novel" else null,
            releaseYear = 2009 + index % 6,
            developer = if (index == 2) "Example Soft" else null,
        )
    }

    private val psxTitles = listOf(
        "Midnight Ferry", "Crystal Garden Tale", "Lantern Road", "Echoes of Kyoto", "Iron Orchard",
        "Snowbound Signal", "Velvet Comet", "Tidewater Saga", "Paper Moon Racing", "Old Capital Ghosts",
    )

    /**
     * PlayStation games with spines, logos and disc cut-outs, all fake paths [FakeCovers] draws.
     * Game 5 has no cover (its logo stands in) and game 6 has neither cover nor logo; games 1 and 7
     * have no spine image, so theirs is drawn.
     */
    val psxGames = psxTitles.mapIndexed { index, title ->
        art(200L + index, 2, "psx", title, index).copy(
            coverPath = if (index == 5 || index == 6) null else "/covers/psx/$index.png",
            box2dSidePath = if (index == 1 || index == 7) null else "/spines/psx/$index.png",
            logoPath = if (index == 6) null else "/logos/$title",
            physicalPath = "/physical/disc/$index.png",
        )
    }

    private val dsTitles = listOf(
        "Pocket Atelier", "Sky Whale Courier", "Mystery of Room 7", "Picross Shrine", "Little Fox Bakery",
        "Starlit Kana Drill", "Ghost Train Notes", "Island Radio", "Dragon Homework",
    )

    /** DS games: no disc or card cut-outs (so a card is drawn), and no spine images. */
    val dsGames = dsTitles.mapIndexed { index, title ->
        art(300L + index, 4, "nds", title, index).copy(
            coverPath = "/covers/nds/$index.png",
            logoPath = "/logos/$title",
        )
    }

    private fun art(id: Long, platformId: Long, slug: String, title: String, index: Int) = GameEntity(
        id = id,
        platformId = platformId,
        platformSlug = slug,
        name = title,
        sortTitle = title.lowercase(),
        fsName = "$title.chd",
        fsSizeBytes = (90L + index * 61L) shl 20,
        coverPath = null,
        hasMultipleFiles = false,
        releaseYear = 1996 + index % 5,
    )

    val downloads = mapOf(
        102L to download(102, DownloadState.INSTALLED, done = 374L shl 20, total = 374L shl 20),
        104L to download(104, DownloadState.RUNNING, done = 220L shl 20, total = 568L shl 20),
        107L to download(107, DownloadState.FAILED, error = "server closed the connection"),
        109L to download(109, DownloadState.INSTALLED, done = 1, total = 1),
        // The PlayStation shelf: games 2 to 5 are here, 1 is downloading and 6 failed.
        201L to download(201, DownloadState.RUNNING, done = 140L shl 20, total = 151L shl 20),
        202L to download(202, DownloadState.INSTALLED, done = 1, total = 1),
        203L to download(203, DownloadState.INSTALLED, done = 1, total = 1),
        204L to download(204, DownloadState.INSTALLED, done = 1, total = 1),
        205L to download(205, DownloadState.INSTALLED, done = 1, total = 1),
        206L to download(206, DownloadState.FAILED, error = "server closed the connection"),
        // The DS shelf: games 2 to 4 are here.
        302L to download(302, DownloadState.INSTALLED, done = 1, total = 1),
        303L to download(303, DownloadState.INSTALLED, done = 1, total = 1),
        304L to download(304, DownloadState.INSTALLED, done = 1, total = 1),
    )

    private fun download(id: Long, state: DownloadState, done: Long = 0, total: Long = 0, error: String? = null) =
        DownloadEntity(
            romId = id,
            name = (games + psxGames + dsGames).first { it.id == id }.name,
            platformSlug = "psp",
            state = state,
            bytesDone = done,
            bytesTotal = total,
            path = "/storage/emulated/0/ROMs/psp/$id.iso",
            error = error,
            queuedAt = NOW,
        )

    val settings by lazy {
        SetupState(
            status = DualScreenStatus(
                mode = ScreenMode.DUAL,
                lowerDisplayId = 4,
                secondaryDisplayId = 4,
                isDefaultHome = true,
                homeAliasEnabled = true,
            ),
            romm = RomMState.SignedIn(
                RomMAccount(
                    baseUrl = "https://romm.example.com/",
                    token = "fake-token",
                    deviceId = null,
                    userId = 1,
                    username = "player1",
                    serverVersion = "4.0.0",
                ),
            ),
            sync = SyncStatus.Done(games = 64),
            // Two rows that need attention, to show their status.
            usageAccess = false,
            shizuku = Privileged.State.STOPPED,
            pspDebugger = false,
            azaharRpc = false,
            builtInPlayer = true,
            textSamples = false,
            dictionaries = dictionaries,
        )
    }

    val dictionaries = listOf("Sample Terms", "Sample Frequencies", "Sample Pitch").mapIndexed { index, title ->
        DictionaryEntity(
            id = index + 1L,
            title = title,
            revision = "1",
            folder = "dict$index",
            sourceName = "$title.zip",
            terms = if (index == 0) 1000 else 0,
            frequencies = if (index == 1) 1000 else 0,
            pitches = if (index == 2) 1000 else 0,
            kanji = 0,
            position = index,
            enabled = index != 2,
            importedAt = NOW,
        )
    }
}

/**
 * Placeholder art drawn on the spot, so no game art is ever committed. A model is "fake:" and an
 * asset path from [FakeData]: covers are two-tone gradients with a pale band (square for the
 * PlayStation, a little taller than wide for the DS), spines a dark strip with a title bar,
 * logos the title in outlined letters on a transparent ground, and physical cut-outs a plain
 * disc. Anything else (the details screen's "fake-cover:") gets a portrait cover.
 */
private object FakeCovers {
    private val palette = listOf(
        0xFF3B5BA5 to 0xFF1B2A4E, 0xFFB5523B to 0xFF4E1F15, 0xFF3F8F6B to 0xFF173A2B,
        0xFF8A4FB0 to 0xFF35184A, 0xFFC9A13B to 0xFF5A4410, 0xFF2F8FA8 to 0xFF0F3945,
    )

    fun forModel(model: String): Bitmap {
        val path = model.removePrefix("fake:")
        return when {
            path.startsWith("/spines/") -> spine(model)
            path.startsWith("/logos/") -> logo(path.removePrefix("/logos/"))
            path.startsWith("/physical/") -> disc(model)
            path.startsWith("/covers/psx/") -> cover(model, 400, 400)
            path.startsWith("/covers/nds/") -> cover(model, 360, 400)
            else -> cover(model, 300, 400)
        }
    }

    private fun cover(model: String, width: Int, height: Int): Bitmap {
        val (top, bottom) = palette[(model.hashCode() and 0x7fffffff) % palette.size]
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top.toInt(), bottom.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = 0x33FFFFFF
        canvas.drawRect(0f, height * 0.62f, width.toFloat(), height * 0.78f, paint)
        paint.color = 0x55FFFFFF
        canvas.drawCircle(width * 0.7f, height * 0.3f, width * 0.16f, paint)
        return bitmap
    }
}

/** A spine: the cover's darker colour, a light title bar and a console band at the top. */
private fun FakeCovers.spine(model: String): Bitmap {
    val bitmap = Bitmap.createBitmap(40, 400, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(0xFF22262E.toInt())
    paint.color = 0xFFB8BEC9.toInt()
    canvas.drawRect(0f, 0f, 40f, 46f, paint)
    paint.color = 0xCCFFFFFF.toInt()
    canvas.drawRect(12f, 70f, 28f, 300f, paint)
    paint.color = (model.hashCode() or 0xFF000000.toInt())
    canvas.drawRect(0f, 340f, 40f, 400f, paint)
    return bitmap
}

/** A title logo: the title in bold white letters with a dark outline, on transparency. */
private fun FakeCovers.logo(title: String): Bitmap {
    val bitmap = Bitmap.createBitmap(480, 200, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val words = title.split(" ")
    val lines = if (words.size > 2) listOf(words.take(words.size / 2).joinToString(" "), words.drop(words.size / 2).joinToString(" ")) else listOf(title)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textSize = 64f
    }
    // Shrink long titles to fit the logo's width, as a real wordmark would be drawn.
    val widest = lines.maxOf { paint.measureText(it) }
    if (widest > 440f) paint.textSize = 64f * 440f / widest
    lines.forEachIndexed { i, line ->
        val y = 200f / 2 + (i - (lines.size - 1) / 2f) * 72f + 22f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 10f
        paint.color = 0xFF2A1B10.toInt()
        canvas.drawText(line, 240f, y, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFE9A8.toInt()
        canvas.drawText(line, 240f, y, paint)
    }
    return bitmap
}

/** A disc cut-out: a silver disc with a printed label and the hole, on transparency. */
private fun FakeCovers.disc(model: String): Bitmap {
    val size = 400
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val c = size / 2f
    paint.color = 0xFFD5D8DE.toInt()
    canvas.drawCircle(c, c, c, paint)
    paint.color = (model.hashCode() or 0xFF000000.toInt())
    canvas.drawCircle(c, c, c * 0.92f, paint)
    paint.color = 0x55FFFFFF
    canvas.drawRect(0f, c * 0.55f, size.toFloat(), c * 0.85f, paint)
    paint.color = 0xFFD5D8DE.toInt()
    canvas.drawCircle(c, c, c * 0.24f, paint)
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    canvas.drawCircle(c, c, c * 0.1f, paint)
    return bitmap
}
