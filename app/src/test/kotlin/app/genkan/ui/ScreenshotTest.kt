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

    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun library() = shoot("library", TOP_PIXELS) {
        LibraryContent(
            platforms = FakeData.platforms,
            games = FakeData.games,
            focus = LibraryFocus(pane = LibraryPane.GAMES, platformIndex = 2, gameIndex = 2),
            sync = SyncStatus.Done(games = 64),
            downloads = FakeData.downloads,
            notice = null,
            coverModel = { game -> if (game.coverPath != null) "fake-cover:${game.id}" else null },
            emulatorFor = { "PPSSPP" },
            onSelectPlatform = {},
            onColumns = {},
            onSelectGame = {},
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
        PlatformEntity(4, "n3ds", "3ds", "Nintendo 3DS", 16, null),
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

    val downloads = mapOf(
        102L to download(102, DownloadState.INSTALLED, done = 374L shl 20, total = 374L shl 20),
        104L to download(104, DownloadState.RUNNING, done = 220L shl 20, total = 568L shl 20),
        107L to download(107, DownloadState.FAILED, error = "server closed the connection"),
        109L to download(109, DownloadState.INSTALLED, done = 1, total = 1),
    )

    private fun download(id: Long, state: DownloadState, done: Long = 0, total: Long = 0, error: String? = null) =
        DownloadEntity(
            romId = id,
            name = games.first { it.id == id }.name,
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

/** Placeholder cover art drawn on the spot: a two-tone gradient with a pale band, per model. */
private object FakeCovers {
    private val palette = listOf(
        0xFF3B5BA5 to 0xFF1B2A4E, 0xFFB5523B to 0xFF4E1F15, 0xFF3F8F6B to 0xFF173A2B,
        0xFF8A4FB0 to 0xFF35184A, 0xFFC9A13B to 0xFF5A4410, 0xFF2F8FA8 to 0xFF0F3945,
    )

    fun forModel(model: String): Bitmap {
        val (top, bottom) = palette[(model.hashCode() and 0x7fffffff) % palette.size]
        val width = 300
        val height = 400
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
