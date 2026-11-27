package app.genkan.ui

import app.genkan.launch.applyTo
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntSize
import app.genkan.dictionary.DictionaryEntity
import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadState
import app.genkan.home.DualScreenStatus
import app.genkan.home.ScreenMode
import app.genkan.library.FocusedGame
import app.genkan.library.GameCase
import app.genkan.library.MediaKind
import app.genkan.library.GameEntity
import app.genkan.library.LibraryFocus
import app.genkan.library.LibraryOrder
import app.genkan.library.LibrarySort
import app.genkan.library.LibraryPane
import app.genkan.library.LibraryView
import app.genkan.anki.AnkiStatus
import app.genkan.library.PlatformEntity
import app.genkan.library.SyncStatus
import app.genkan.play.PlayTotals
import app.genkan.privileged.Privileged
import app.genkan.romm.RomMAccount
import app.genkan.romm.RomMState
import app.genkan.saves.SaveStatus
import app.genkan.stats.ReadingGameTotals
import app.genkan.stats.ReadingLogStatus
import app.genkan.yomikata.CatalogStatus
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import com.github.takahirom.roborazzi.RoborazziOptions
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

    /** "Downloaded only" on, flipped with L3 from the console strip: the header says "3 of 8". */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun shelfDownloadedOnly() = shoot("shelf_downloaded_only", TOP_PIXELS) {
        library(
            LibraryView.SHELF, platformIndex = 1, FakeData.psxGames.subList(2, 5), gameIndex = 1, staticMedia = true,
            order = LibraryOrder(downloadedOnly = true), pane = LibraryPane.PLATFORMS,
        )
    }

    /** "Continue playing" with a PC game played last: its plain tile first, the consoles' cases after it. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun shelfContinuePcFirst() = shoot("shelf_continue_pc_first", TOP_PIXELS) {
        library(
            LibraryView.SHELF,
            platformIndex = 0,
            listOf(FakeData.pcGame) + FakeData.psxGames.take(3) + FakeData.dsGames.take(2),
            gameIndex = 1,
            staticMedia = true,
            platforms = listOf(app.genkan.library.ContinuePlaying.platform(6)) + FakeData.platforms,
        )
    }

    /** The full-box prototype at four turns: 0, a three-quarter view, side-on, and past 90 to the back. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun fullBoxTurns() = shoot("full_box_turns", TOP_PIXELS) {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF202024)),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            for (angle in listOf(0f, 28f, 80f, 160f)) {
                FullBox(
                    front = "fake:/covers/psx/1.png",
                    spine = "fake:/spines/psx/1.png",
                    back = "fake:/covers/psx/2.png",
                    maxWidth = androidx.compose.ui.unit.Dp(200f),
                    maxHeight = androidx.compose.ui.unit.Dp(300f),
                    angle = angle,
                )
            }
        }
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

    /**
     * The focused game drawn as a full box (the Full box setting is on by default): it has a front
     * and a spine scan. Its disc peeks out of the box as from the case, and the box keeps the
     * case's download badge; the games around it keep their cases.
     */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun shelfFullBox() = shoot("shelf_full_box", TOP_PIXELS) {
        val games = FakeData.psxGames.mapIndexed { i, game -> if (i == 3) game.copy(box2dPath = "/covers/psx/${game.id}.png") else game }
        library(LibraryView.SHELF, platformIndex = 1, games, gameIndex = 3, staticMedia = true)
    }

    /**
     * The focused full box turned round to its back with R3 (the game has a back scan), the hint
     * offering the front again; it's the same height as facing front.
     */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun shelfFullBoxBack() = shoot("shelf_full_box_back", TOP_PIXELS) {
        val games = FakeData.psxGames.mapIndexed { i, game ->
            if (i == 3) game.copy(box2dPath = "/covers/psx/${game.id}.png", box2dBackPath = "/covers/psx/${game.id + 1}.png") else game
        }
        library(LibraryView.SHELF, platformIndex = 1, games, gameIndex = 3, staticMedia = false, turn = ShelfTurn(turnedId = games[3].id) { it.box2dBackPath != null })
    }

    /** A full box whose game isn't downloaded: the case's download badge, in the box's corner. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun shelfFullBoxBadge() = shoot("shelf_full_box_badge", TOP_PIXELS) {
        val games = FakeData.psxGames.mapIndexed { i, game -> if (i == 0) game.copy(box2dPath = "/covers/psx/${game.id}.png") else game }
        library(LibraryView.SHELF, platformIndex = 1, games, gameIndex = 0)
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

    /** Covers on their way: the focused game and the ones after it show their titles until they load. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun shelfLoading() = shoot("shelf_loading", TOP_PIXELS) {
        val games = FakeData.psxGames.mapIndexed { index, game -> if (index >= 7) game.copy(coverPath = "/loading/$index.png") else game }
        library(LibraryView.SHELF, platformIndex = 1, games, gameIndex = 8, staticMedia = false)
    }

    /** The "Sort & filter" panel open over the shelf (L2), on the sort. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun shelfOrderPanel() = shoot("shelf_order_panel", TOP_PIXELS) {
        library(
            LibraryView.SHELF, platformIndex = 1, FakeData.psxGames, gameIndex = 3, staticMedia = false,
            order = LibraryOrder(LibrarySort.LAST_PLAYED, downloadedOnly = true), orderPanel = 0,
        )
    }

    /** "Downloaded only" with nothing of the console on the device. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun shelfNothingDownloaded() = shoot("shelf_nothing_downloaded", TOP_PIXELS) {
        library(LibraryView.SHELF, platformIndex = 1, emptyList(), gameIndex = 0, order = LibraryOrder(downloadedOnly = true))
    }

    /** A PlayStation game's disc (the server's cut-out) dropped onto the spindle and spinning up. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun insertDisc() = shoot("insert_disc", TOP_PIXELS) {
        insertFrame(platformIndex = 1, FakeData.psxGames, gameIndex = 3, progress = 0.8f)
    }

    /** A DS game with no cut-out: a drawn card going down into its slot. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun insertCard() = shoot("insert_card", TOP_PIXELS) {
        insertFrame(platformIndex = 3, FakeData.dsGames, gameIndex = 2, progress = 0.45f)
    }

    /** A PSP game: a drawn UMD in its bay, the door swinging shut. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun insertUmd() = shoot("insert_umd", TOP_PIXELS) {
        insertFrame(platformIndex = 2, FakeData.games, gameIndex = 2, progress = 0.78f)
    }

    /** The shelf with [games]' game at [gameIndex] being started, its insert animation fixed at [progress]. */
    @Composable
    private fun insertFrame(platformIndex: Int, games: List<GameEntity>, gameIndex: Int, progress: Float) {
        val game = games[gameIndex]
        val insert = InsertState(
            gameId = game.id,
            title = game.name,
            kind = MediaKind.forSlug(game.platformSlug),
            spec = GameCase.specFor(game.platformSlug),
            physical = game.physicalPath?.let { "fake:$it" },
            logo = game.logoPath?.let { "fake:$it" },
            durationMs = 1_500,
        )
        library(LibraryView.SHELF, platformIndex, games, gameIndex, staticMedia = true, insert = insert, insertProgress = progress)
    }

    /** The library on [platforms][FakeData.platforms] index [platformIndex], showing [games]. */
    @Composable
    private fun library(
        view: LibraryView,
        platformIndex: Int,
        games: List<GameEntity>,
        gameIndex: Int,
        staticMedia: Boolean? = null,
        insert: InsertState? = null,
        insertProgress: Float? = null,
        order: LibraryOrder = LibraryOrder(),
        orderPanel: Int? = null,
        pane: LibraryPane = LibraryPane.GAMES,
        platforms: List<PlatformEntity> = FakeData.platforms,
        turn: ShelfTurn = ShelfTurn(),
    ) {
        LibraryContent(
            view = view,
            platforms = platforms,
            games = games,
            focus = LibraryFocus(pane = pane, platformIndex = platformIndex, gameIndex = gameIndex),
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
            insert = insert,
            staticInsertProgress = insertProgress,
            order = order,
            orderPanel = orderPanel,
            turn = turn,
        )
    }

    /**
     * Backlog lines with the game's own furigana (made-up text, as a Yomikata script gives them):
     * readings over their kanji, one over a word broken across rows; a looked-up word highlighted;
     * a line without any, at the usual height.
     */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun backlogFurigana() = shoot("backlog_furigana", BOTTOM_PIXELS) {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxSize().background(androidx.tv.material3.MaterialTheme.colorScheme.background)
                .padding(androidx.compose.ui.unit.Dp(16f)),
        ) {
            val first = "古い港町の灯台に、今夜も明かりが灯った。"
            TappableLine(
                first, start = 4, end = 6, marks = emptyList(), onMiss = {},
                ruby = listOf(app.genkan.reader.Ruby(0, 1, "ふる"), app.genkan.reader.Ruby(2, 4, "みなとまち"), app.genkan.reader.Ruby(5, 7, "とうだい"),
                    app.genkan.reader.Ruby(9, 11, "こんや"), app.genkan.reader.Ruby(12, 13, "あ"), app.genkan.reader.Ruby(16, 17, "とも")),
            ) {}
            TappableLine("見張りの交代は、まだ先だ。", start = null, end = null, marks = emptyList(), onMiss = {}) {}
            val long = "遠くの海から帰ってきた船乗りたちは、口々に不思議な光の話をした。"
            TappableLine(
                long, start = null, end = null, marks = emptyList(), onMiss = {},
                ruby = listOf(app.genkan.reader.Ruby(0, 1, "とお"), app.genkan.reader.Ruby(3, 4, "うみ"), app.genkan.reader.Ruby(6, 7, "かえ"),
                    app.genkan.reader.Ruby(11, 13, "ふなの"), app.genkan.reader.Ruby(18, 20, "くちぐち"), app.genkan.reader.Ruby(21, 24, "ふしぎ"),
                    app.genkan.reader.Ruby(25, 26, "ひかり"), app.genkan.reader.Ruby(27, 28, "はなし")),
            ) {}
        }
    }

    private fun swapState(kind: app.genkan.library.MediaKind, slug: String, from: String, to: String) = DiskSwapState(
        key = 1, title = "A Game", kind = kind, spec = app.genkan.library.GameCase.specFor(slug),
        physical = null, logo = null, from = from, to = to,
    )

    /** A Disk System game's disk lifted out of the drive, side A going out (made-up title, no server art). */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun diskSwapOut() = shoot("disk_swap_out", BOTTOM_PIXELS) {
        DiskSwapOverlay(swapState(app.genkan.library.MediaKind.DISK_CARD, "fds", "Disk 1, side A", "Disk 1, side B"), staticProgress = 0.22f)
    }

    /** The other side sliding in from the right. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun diskSwapIn() = shoot("disk_swap_in", BOTTOM_PIXELS) {
        DiskSwapOverlay(swapState(app.genkan.library.MediaKind.DISK_CARD, "fds", "Disk 1, side A", "Disk 1, side B"), staticProgress = 0.5f)
    }

    /** A set's next disc in and spinning, and which one it is. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun discSwapDone() = shoot("disc_swap_done", BOTTOM_PIXELS) {
        DiskSwapOverlay(swapState(app.genkan.library.MediaKind.DISC, "dc", "Disc 1 of 3", "Disc 2 of 3"), staticProgress = 1f)
    }

    /** A choice a layout reads (made-up answers), the cursor on the second, under the backlog. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun choiceList() = shoot("choice_list", BOTTOM_PIXELS) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(androidx.tv.material3.MaterialTheme.colorScheme.background),
            contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
        ) {
            ChoiceList(listOf("もう一度、見てみる", "誰かに相談する", "そのまま帰る"), cursor = 1)
        }
    }

    /** A long choice (made-up answers): the list capped at a third of the screen, scrolled to the cursor on the last. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun choiceListLong() = shoot("choice_list_long", BOTTOM_PIXELS) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            androidx.compose.ui.Modifier.fillMaxSize().background(androidx.tv.material3.MaterialTheme.colorScheme.background),
            contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
        ) {
            ChoiceList(
                listOf(
                    "もう一度だけ、あの場所に戻ってよく見てみる",
                    "誰か相談できそうな人がいないか、思い出してみる",
                    "今日はもう遅いから、いったん家に帰ることにする",
                    "このまま朝まで、ここで待ってみることにする",
                    "何も言わずに、その場を離れる",
                ),
                cursor = 4,
                modifier = androidx.compose.ui.Modifier.heightIn(max = maxHeight * 0.33f),
            )
        }
    }

    /** A game with everything: artwork, RomM's details, your play, reading and save. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun gameDetails() = shoot("game_details", BOTTOM_PIXELS) {
        GameDetailsContent(details = FakeData.fullDetails, onShowReader = {})
    }

    /** A game with the built-in player on: the look button, taking its console's. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun gameDetailsLook() = shoot("game_details_look", BOTTOM_PIXELS) {
        // Scrolled to the end of the buttons, where the look and Delete are.
        GameDetailsContent(
            details = FakeData.fullDetails.copy(look = GameLook(null, app.genkan.core.ScreenLook.SHARP)),
            onShowReader = {},
            actionsScroll = rememberScrollState(Int.MAX_VALUE),
        )
    }

    /** "Open manual" while the manual is fetched: a ring filled to how far along it is, on the button, in view. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun gameDetailsManualFetching() = shoot("game_details_manual_fetching", BOTTOM_PIXELS) {
        GameDetailsContent(details = FakeData.fullDetails, onShowReader = {}, manualFetch = app.genkan.manual.ManualFetch.Busy(0.42f))
    }

    /** The reader's menu open under its button: every row, a look not kept and a manual that failed marked. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun readerMenu() = shoot("reader_menu", BOTTOM_PIXELS) {
        val items = ReaderMenu.items(
            ReaderBarState(
                manual = true,
                manualFetch = app.genkan.manual.ManualFetch.Failed("Couldn't fetch the manual"),
                ocrOn = true,
                capturing = true,
                engine = "ML Kit",
                overlayAvailable = true,
                overlayOn = true,
                look = "CRT" to false,
                thread = "1",
            ),
        )
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.tv.material3.MaterialTheme.colorScheme.background)) {
            androidx.compose.foundation.layout.Row(
                androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(androidx.compose.ui.unit.Dp(12f)),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(androidx.compose.ui.unit.Dp(12f)),
            ) {
                Chip("OCR on") {}
                Chip("Snapshot") {}
                ReaderMenuButton(open = true, attention = items.any { it.attention }) {}
            }
            ReaderMenuPanel(items, onPick = {}, modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(top = androidx.compose.ui.unit.Dp(64f), end = androidx.compose.ui.unit.Dp(12f)))
        }
    }

    /** The headphones list: one connected, one not, after a tap opened Android's output picker. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun headphonesList() = shoot("headphones_list", BOTTOM_PIXELS) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.tv.material3.MaterialTheme.colorScheme.background)) {
            HeadphonesList(
                allowed = true,
                shizuku = false,
                devices = listOf(
                    app.genkan.bluetooth.Headphones.Device("00:00:00:00:00:01", "Earbuds", connected = true),
                    app.genkan.bluetooth.Headphones.Device("00:00:00:00:00:02", "Speaker", connected = false),
                ),
                status = app.genkan.bluetooth.Headphones.Status.Opened("Speaker", app.genkan.bluetooth.ConnectRoute.Step.OUTPUT_PICKER),
                onAllow = {},
                onPick = {},
                modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopEnd)
                    .padding(top = androidx.compose.ui.unit.Dp(64f), end = androidx.compose.ui.unit.Dp(12f))
                    .width(androidx.compose.ui.unit.Dp(420f)),
            )
        }
    }

    /** A fetch that failed, said on the same button for a few seconds. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun gameDetailsManualFailed() = shoot("game_details_manual_failed", BOTTOM_PIXELS) {
        GameDetailsContent(details = FakeData.fullDetails, onShowReader = {}, manualFetch = app.genkan.manual.ManualFetch.Failed("Couldn't fetch the manual"))
    }

    /** The same five buttons at rest: the first three whole, the art smaller, the column fading where it goes on. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun gameDetailsButtons() = shoot("game_details_buttons", BOTTOM_PIXELS) {
        GameDetailsContent(details = FakeData.fullDetails.copy(look = GameLook(null, app.genkan.core.ScreenLook.SHARP)), onShowReader = {})
    }

    /** A PSP game whose text PPSSPP's hook reads: the chip under its details line. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun gameDetailsTextChip() = shoot("game_details_text_chip", BOTTOM_PIXELS) {
        GameDetailsContent(details = FakeData.fullDetails.copy(textSource = app.genkan.yomikata.TextSource.HOOK), onShowReader = {})
    }

    /** The same game scrolled to the end of its details, where the screenshot strip is. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun gameDetailsScrolled() = shoot("game_details_scrolled", BOTTOM_PIXELS) {
        // The scroll position is clamped to the column's height once it is laid out.
        GameDetailsContent(details = FakeData.fullDetails, onShowReader = {}, factsScroll = rememberScrollState(Int.MAX_VALUE))
    }

    /**
     * A game RomM knows little about, never downloaded or played: most lines are left out, and
     * with no cut-out or fan art the cover stands in, blurred behind as well.
     */
    /** A PC game's details once Steam's store has answered: its Japanese name, summary, genres and screenshots. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun gameDetailsPc() = shoot("game_details_pc", BOTTOM_PIXELS) {
        val game = app.genkan.library.GameEntity(
            -1_004_242, app.genkan.launch.LocalShelves.PC_ID, app.genkan.launch.LocalShelves.PC_SLUG,
            "Clock Tower", "clock tower", "4242", 0, null, false,
        )
        val steam = app.genkan.launch.SteamDetails(
            name = "時計塔の弟子",
            summary = "止まった時計をもう一度動かすため、見習いの少年が町の塔をひとつずつ巡る。全七章の物語。",
            genres = listOf("アドベンチャー", "インディー"),
            releaseYear = 2019,
            developers = listOf("Example Soft"),
            publishers = listOf("Sample Publishing"),
            background = "fake-fanart:4242",
            screenshots = listOf("fake-screenshot:4242-1", "fake-screenshot:4242-2", "fake-screenshot:4242-3"),
        )
        GameDetailsContent(
            details = GameDetails(
                focus = FocusedGame.Focus(steam.applyTo(game), "PC (GameNative)", "fake-cover:${game.id}"),
                play = null,
                reading = ReadingGameTotals(activeMs = 0, characters = 0),
                download = null,
                art = GameArt(fanart = steam.background, screenshots = steam.screenshots),
            ),
            onShowReader = {},
        )
    }

    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun gameDetailsSparse() = shoot("game_details_sparse", BOTTOM_PIXELS) {
        val game = FakeData.games[6]
        GameDetailsContent(
            details = GameDetails(
                focus = FocusedGame.Focus(game, "PlayStation Portable", "fake-cover:${game.id}"),
                play = null,
                reading = ReadingGameTotals(activeMs = 0, characters = 0),
                download = null,
            ),
            onShowReader = {},
        )
    }

    /** The full game's second screenshot opened over the whole screen. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun gameDetailsScreenshot() = shoot("game_details_screenshot", BOTTOM_PIXELS) {
        GameDetailsContent(details = FakeData.fullDetails, onShowReader = {}, initialScreenshot = 1)
    }

    /** A twelve-page manual open in the viewer, at its first page; [FakeManual] draws the pages. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun manualViewer() = shoot("manual_viewer", BOTTOM_PIXELS) {
        ManualViewer(FakeManual(pages = 12), onClose = {})
    }

    /** A word looked up on a page while playing: boxed on the page, the bar saying a page is being read. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun manualViewerLookup() = shoot("manual_viewer_lookup", BOTTOM_PIXELS) {
        ManualViewer(
            FakeManual(pages = 12),
            onClose = {},
            closeLabel = "Back to reader",
            onTapText = { _, _, _, _, _ -> },
            status = "Reading this page…",
            // Over the third grey line beside the picture box.
            mark = ManualMark(0, app.genkan.ocr.ScreenText.Box(0.52f, 0.29f / 1.294f, 0.70f, 0.306f / 1.294f)),
        )
    }

    /**
     * A word looked up on the manual while playing: boxed on its page, the lookup's panel over the
     * manual on the other half from the tap (a tap in the upper half: the panel below), a stand-in where the dictionary's entries go.
     */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun manualLookupPanelBelow() = shoot("manual_lookup_panel_below", BOTTOM_PIXELS) {
        val word = app.genkan.dictionary.TermHit(matched = "とけい", deinflected = "とけい", expression = "時計", reading = "とけい")
        ManualWithLookup(
            lookup = Lookup(MANUAL_LINE, 0, app.genkan.dictionary.LookupResult("とけいを", listOf(word), "[]"), "とけいをまく"),
            onTop = false,
            onCloseLookup = {},
            popup = { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF34343C))) },
        ) {
            ManualViewer(
                FakeManual(pages = 12),
                onClose = {},
                closeLabel = "Back to reader",
                onTapText = { _, _, _, _, _ -> },
                mark = ManualMark(0, app.genkan.ocr.ScreenText.Box(0.52f, 0.29f / 1.294f, 0.70f, 0.306f / 1.294f)),
            )
        }
    }

    /**
     * A word looked up on the manual while playing: boxed on its page, the lookup's panel over the
     * manual on the other half from the tap (a tap in the lower half: the panel above), a stand-in where the dictionary's entries go.
     */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun manualLookupPanelAbove() = shoot("manual_lookup_panel_above", BOTTOM_PIXELS) {
        val word = app.genkan.dictionary.TermHit(matched = "とけい", deinflected = "とけい", expression = "時計", reading = "とけい")
        ManualWithLookup(
            lookup = Lookup(MANUAL_LINE, 0, app.genkan.dictionary.LookupResult("とけいを", listOf(word), "[]"), "とけいをまく"),
            onTop = true,
            onCloseLookup = {},
            popup = { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF34343C))) },
        ) {
            ManualViewer(
                FakeManual(pages = 12),
                onClose = {},
                closeLabel = "Back to reader",
                onTapText = { _, _, _, _, _ -> },
                mark = ManualMark(0, app.genkan.ocr.ScreenText.Box(0.06f, 65f / 129.4f, 0.40f, 66.6f / 129.4f)),
            )
        }
    }

    /** A word looked up on the manual opened from a game's details: as during play, with Close for the way out. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun manualLookupFromDetails() = shoot("manual_lookup_details", BOTTOM_PIXELS) {
        val word = app.genkan.dictionary.TermHit(matched = "はり", deinflected = "はり", expression = "針", reading = "はり")
        ManualWithLookup(
            lookup = Lookup(MANUAL_LINE, 0, app.genkan.dictionary.LookupResult("はりを", listOf(word), "[]"), "はりをまわす"),
            onTop = false,
            onCloseLookup = {},
            popup = { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF34343C))) },
        ) {
            ManualViewer(
                FakeManual(pages = 12),
                onClose = {},
                onTapText = { _, _, _, _, _ -> },
                mark = ManualMark(0, app.genkan.ocr.ScreenText.Box(0.52f, 0.33f / 1.294f, 0.66f, 0.346f / 1.294f)),
            )
        }
    }

    /** The same manual scrolled to where page 4 ends and page 5 begins. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun manualViewerScrolled() = shoot("manual_viewer_scrolled", BOTTOM_PIXELS) {
        ManualViewer(FakeManual(pages = 12), onClose = {}, listState = rememberLazyListState(3, 1010))
    }

    /** The status card, with the group list focused; the other groups with the pad on their rows. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun settingsStatus() = shootSettings(0, SettingsFocus(pane = SettingsPane.GROUPS, group = 0))

    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun settingsLibrary() = shootSettings(1, SettingsFocus(pane = SettingsPane.ROWS, group = 1, row = 0))

    /** The list of looks for the PlayStation, from the screen look page: the pad on the current one. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun screenLookPicker() = shoot("screen_look_picker", TOP_PIXELS) {
        ScreenLookContent(consoles = mapOf("psx" to app.genkan.core.ScreenLook.CRT_LOTTES), picking = "psx")
    }

    /** A PSP game's list, filtered to the looks that suit a PSP, the show-all row first. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun gameLookPickerFiltered() = shoot("game_look_picker_filtered", BOTTOM_PIXELS) {
        LookPicker(
            title = "The Clockmaker's Apprentice: look",
            selected = null,
            console = app.genkan.core.ScreenLook.DEFAULT,
            suits = app.genkan.core.ScreenLook.suiting("psp"),
            onPick = {},
            onClose = {},
        )
    }

    /** The same list with every look shown. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun gameLookPickerAll() = shoot("game_look_picker_all", BOTTOM_PIXELS) {
        LookPicker(
            title = "The Clockmaker's Apprentice: look",
            selected = null,
            console = app.genkan.core.ScreenLook.DEFAULT,
            suits = app.genkan.core.ScreenLook.suiting("psp"),
            onPick = {},
            onClose = {},
            showAllAtFirst = true,
        )
    }

    /** The list of looks for one game, on the bottom screen: "Console's" first and in use. */
    @Test
    @Config(qualifiers = BOTTOM_SCREEN)
    fun gameLookPicker() = shoot("game_look_picker", BOTTOM_PIXELS) {
        LookPicker(
            title = "The Clockmaker's Apprentice: look",
            selected = null,
            console = app.genkan.core.ScreenLook.SHARP,
            onPick = {},
            onClose = {},
        )
    }

    /** The PlayStation's look list on the pad, one Right from the look in use: the "Show all" chip focused. */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun screenLookPickerChipFocused() = shoot("screen_look_picker_chip_focused", TOP_PIXELS) {
        androidx.compose.ui.platform.LocalInputModeManager.current.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
        ScreenLookContent(consoles = mapOf("psx" to app.genkan.core.ScreenLook.CRT_LOTTES), picking = "psx")
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        androidx.compose.runtime.LaunchedEffect(Unit) {
            // The list focuses the look in use a frame after it opens; Right then goes to the chip.
            repeat(3) { androidx.compose.runtime.withFrameNanos {} }
            focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Right)
        }
    }

    /** The screen look page: the defaults, with the GBA and PSP chosen. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun screenLook() = shoot("screen_look", TOP_PIXELS) {
        ScreenLookContent(
            consoles = mapOf("gba" to app.genkan.core.ScreenLook.SHARP, "psp" to app.genkan.core.ScreenLook.CUT),
        )
    }

    /** The screen look page's Plays with rows: the PS2 on ARMSX2. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun playsWith() = shoot("screen_look_plays_with", TOP_PIXELS) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(androidx.tv.material3.MaterialTheme.colorScheme.background)
                .padding(horizontal = androidx.compose.ui.unit.Dp(48f), vertical = androidx.compose.ui.unit.Dp(32f)),
        ) {
            PlaysWith(
                consoles = listOf("ps2" to "PlayStation 2"),
                choice = mapOf("ps2" to app.genkan.core.CoreCatalog.EXTERNAL),
                onCycle = {},
            )
        }
    }

    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun settingsConsoles() = shootSettings(2, SettingsFocus(pane = SettingsPane.ROWS, group = 2, row = 1))

    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun settingsControls() = shootSettings(3, SettingsFocus(pane = SettingsPane.ROWS, group = 3, row = 0))

    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun settingsReading() = shootSettings(4, SettingsFocus(pane = SettingsPane.ROWS, group = 4, row = 0))

    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun settingsAnki() = shootSettings(5, SettingsFocus(pane = SettingsPane.ROWS, group = 5, row = 1))

    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun settingsConnections() = shootSettings(6, SettingsFocus(pane = SettingsPane.ROWS, group = 6, row = 0))

    /** The last groups: the list scrolled to keep the focused group in view. */
    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun settingsDevice() = shootSettings(7, SettingsFocus(pane = SettingsPane.ROWS, group = 7, row = 1))

    @Test
    @Config(qualifiers = TOP_SCREEN)
    fun settingsAbout() = shootSettings(8, SettingsFocus(pane = SettingsPane.GROUPS, group = 8))

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
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = COMPARE) {
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
        const val BOTTOM_SCREEN = "w538dp-h468dp-land-369dpi"
        val TOP_PIXELS = 1920 to 1080
        val BOTTOM_PIXELS = 1240 to 1080
    }
}

/** Made-up library, downloads and dictionaries; no real hosts, users or games. */
internal object FakeData {
    /** A fixed "now", 2026-01-15 12:00 UTC, so dates in the screenshots never change. */
    const val NOW = 1_768_478_400_000L

    val platforms = listOf(
        PlatformEntity(1, "gba", "gba", "Game Boy Advance", 12, null),
        PlatformEntity(2, "psx", "psx", "PlayStation", 8, null),
        PlatformEntity(3, "psp", "psp", "PlayStation Portable", 14, null),
        PlatformEntity(4, "nds", "nds", "Nintendo DS", 9, null),
        PlatformEntity(-1, "genkan-pc", null, "PC", 3, null),
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

    /** A PC game from GameNative's export: a plain 1:1 tile with no case. */
    val pcGame = GameEntity(
        id = -1_000_001L,
        platformId = -1,
        platformSlug = "genkan-pc",
        name = "Northern Lights Inn",
        sortTitle = "northern lights inn",
        fsName = "1000001",
        fsSizeBytes = 0,
        coverPath = "/covers/pc/0.png",
        hasMultipleFiles = false,
    )

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

    /** The focused game with every artwork and detail column filled. */
    private val fullGame by lazy {
        games[2].copy(
            physicalPath = "/physical/102.png",
            logoPath = "/logo/102.png",
            fanartPath = "/fanart/102.jpg",
            screenshotPaths = "/shots/102-1.png\n/shots/102-2.png\n/shots/102-3.png",
            manualPath = "/manuals/102.pdf",
            publishers = "Example Soft\nSample Publishing",
            regions = "Japan",
            playerCount = "1",
            // 2009-07-13.
            releaseDateMillis = 1_247_443_200_000L,
            hltbMainStorySec = 32 * 3600 + 10 * 60,
            lastPlayedMillis = NOW - 86_400_000L,
            userStatus = "incomplete",
            userCompletion = 40,
            userRating = 8,
            backlogged = true,
        )
    }

    /** What the bottom screen shows for [fullGame]; [FakeCovers] draws its artwork. */
    val fullDetails by lazy {
        GameDetails(
            focus = FocusedGame.Focus(fullGame, "PlayStation Portable", "fake-cover:${fullGame.id}"),
            play = PlayTotals(plays = 7, totalMs = 5 * 3_600_000L + 42 * 60_000L, lastPlayed = NOW - 86_400_000L),
            reading = ReadingGameTotals(activeMs = 3 * 3_600_000L + 10 * 60_000L, characters = 48_213, lines = 1_204),
            download = downloads[fullGame.id],
            save = SaveStatus.Synced(at = NOW - 3_600_000L, what = "uploaded"),
            art = GameArt(
                physical = "fake-physical:${fullGame.id}",
                logo = "fake-logo:${fullGame.name}",
                fanart = "fake-fanart:${fullGame.id}",
                screenshots = (1..3).map { "fake-screenshot:$it" },
                hasManual = true,
            ),
        )
    }

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
            // A fresh catalog, an unreachable reading log: both kinds of status line.
            catalog = CatalogStatus.Ready(entries = 120, updatedAt = NOW - 3 * 3_600_000L),
            anki = AnkiStatus.Sent(5),
            readingLog = ReadingLogStatus.Failed("connection refused"),
            version = "0.0.1 (debug, 1234abc)",
            nowMs = NOW,
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
 * Placeholder art drawn on the spot, so no game art is ever committed. The shelf's models are
 * "fake:" and an asset path from [FakeData]: covers are two-tone gradients with a pale band
 * (square for the PlayStation, a little taller than wide for the DS), spines a dark strip with a
 * title bar, logos the title in outlined letters on a transparent ground, and physical cut-outs
 * a plain disc. The details screen's models go by prefix: a labelled disc for "fake-physical:",
 * the title on a pale plate for "fake-logo:", a wide landscape for "fake-fanart:" and a scene
 * with a text box for "fake-screenshot:". Anything else (as "fake-cover:") gets a portrait cover.
 */
private object FakeCovers {
    private val palette = listOf(
        0xFF3B5BA5 to 0xFF1B2A4E, 0xFFB5523B to 0xFF4E1F15, 0xFF3F8F6B to 0xFF173A2B,
        0xFF8A4FB0 to 0xFF35184A, 0xFFC9A13B to 0xFF5A4410, 0xFF2F8FA8 to 0xFF0F3945,
    )

    fun forModel(model: String): Bitmap {
        val path = model.removePrefix("fake:")
        return when {
            model.startsWith("fake-physical:") -> labelledDisc(model)
            model.startsWith("fake-logo:") -> plateLogo(model.substringAfter(':'))
            model.startsWith("fake-fanart:") -> landscape(model, 640, 360, textBox = false)
            model.startsWith("fake-screenshot:") -> landscape(model, 480, 272, textBox = true)
            path.startsWith("/spines/") -> spine(model)
            path.startsWith("/logos/") -> logo(path.removePrefix("/logos/"))
            // A cover still loading draws nothing, as Coil does before the image arrives.
            path.startsWith("/loading/") -> Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            path.startsWith("/physical/") -> disc(model)
            path.startsWith("/covers/psx/") -> cover(model, 400, 400)
            path.startsWith("/covers/nds/") -> cover(model, 360, 400)
            else -> cover(model, 300, 400)
        }
    }

    private fun colorsFor(model: String) = palette[(model.hashCode() and 0x7fffffff) % palette.size]

    private fun cover(model: String, width: Int, height: Int): Bitmap {
        val (top, bottom) = colorsFor(model)
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

    /** A disc on a transparent square, with a printed label ring and the centre hole cut out. */
    private fun labelledDisc(model: String): Bitmap {
        val (light, dark) = colorsFor(model)
        val size = 400
        val c = size / 2f
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFFD8DCE2.toInt()
        canvas.drawCircle(c, c, c - 4, paint)
        paint.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), light.toInt(), dark.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(c, c, c - 18, paint)
        paint.shader = null
        paint.color = 0x55FFFFFF
        canvas.drawCircle(c, c, c * 0.38f, paint)
        paint.color = 0xFFD8DCE2.toInt()
        canvas.drawCircle(c, c, c * 0.24f, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        canvas.drawCircle(c, c, c * 0.1f, paint)
        return bitmap
    }

    /** The title in heavy type on a pale rounded plate, on a transparent background. */
    private fun plateLogo(title: String): Bitmap {
        val width = 600
        val height = 200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xE6F4E9D0.toInt()
        canvas.drawRoundRect(10f, 30f, width - 10f, height - 30f, 40f, 40f, paint)
        paint.color = 0xFF5A3B12.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.textSize = 48f
        val words = title.split(' ')
        val half = (words.size + 1) / 2
        canvas.drawText(words.take(half).joinToString(" "), width / 2f, 92f, paint)
        canvas.drawText(words.drop(half).joinToString(" "), width / 2f, 146f, paint)
        return bitmap
    }

    /** Sky, sun and hills; [textBox] adds a visual novel's dialogue box along the bottom. */
    private fun landscape(model: String, width: Int, height: Int, textBox: Boolean): Bitmap {
        val (sky, ground) = colorsFor(model)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), sky.toInt(), ground.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = 0x66FFF3C4
        canvas.drawCircle(width * 0.72f, height * 0.3f, height * 0.14f, paint)
        paint.color = 0x55000000
        canvas.drawCircle(width * 0.2f, height * 1.25f, height * 0.75f, paint)
        canvas.drawCircle(width * 0.75f, height * 1.4f, height * 0.8f, paint)
        if (textBox) {
            paint.color = 0xCC101018.toInt()
            canvas.drawRoundRect(width * 0.04f, height * 0.68f, width * 0.96f, height * 0.95f, 12f, 12f, paint)
            paint.color = 0x99FFFFFF.toInt()
            canvas.drawRect(width * 0.08f, height * 0.75f, width * 0.8f, height * 0.79f, paint)
            canvas.drawRect(width * 0.08f, height * 0.84f, width * 0.6f, height * 0.88f, paint)
        }
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
    // A printed band across the label, inside the rim as on a real disc.
    paint.color = 0x55FFFFFF
    canvas.save()
    canvas.clipPath(android.graphics.Path().apply { addCircle(c, c, c * 0.92f, android.graphics.Path.Direction.CW) })
    canvas.drawRect(0f, c * 0.55f, size.toFloat(), c * 0.85f, paint)
    canvas.restore()
    paint.color = 0xFFD5D8DE.toInt()
    canvas.drawCircle(c, c, c * 0.24f, paint)
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    canvas.drawCircle(c, c, c * 0.1f, paint)
    return bitmap
}

/**
 * A manual of US letter pages drawn on the spot, standing in for [PdfManualPages] (Robolectric
 * has no PDF renderer): a title band, a picture box and grey bars for lines of text.
 */
internal class FakeManual(pages: Int) : ManualPages {
    override val pageSizes: List<IntSize> = List(pages) { IntSize(612, 792) }

    override suspend fun render(index: Int, widthPx: Int): Bitmap {
        val (width, height) = PdfManualPages.renderSize(612, 792, widthPx)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val u = width / 100f
        canvas.drawColor(android.graphics.Color.WHITE)
        paint.color = 0xFF8A2E2E.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), 14 * u, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 6 * u
        paint.isFakeBoldText = true
        canvas.drawText("HOW TO PLAY  ${index + 1}", 6 * u, 9.5f * u, paint)
        paint.color = 0xFFDCE3EC.toInt()
        canvas.drawRect(6 * u, 20 * u, 46 * u, 50 * u, paint)
        paint.color = 0xFF9AA5B4.toInt()
        canvas.drawCircle(26 * u, 35 * u, 8 * u, paint)
        paint.color = 0xFFB0B0B0.toInt()
        var y = 21 * u
        while (y < height - 8 * u) {
            val left = if (y < 52 * u) 52 * u else 6 * u
            val right = if ((y / u).toInt() % 7 == 0) 70 * u else 94 * u
            canvas.drawRect(left, y, right, y + 1.6f * u, paint)
            y += 4 * u
        }
        return bitmap
    }

    override fun close() {}
}

/**
 * When verifying (CI's compare step), a shot passes while under 1% of its pixels differ: the PNGs
 * are recorded on macOS and CI renders on Linux, where text antialiasing differs.
 */
private val COMPARE = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01F))
