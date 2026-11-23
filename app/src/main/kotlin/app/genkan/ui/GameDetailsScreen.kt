package app.genkan.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.core.ScreenLook
import app.genkan.download.DownloadState
import app.genkan.launch.LocalShelves
import app.genkan.manual.ManualFetch
import app.genkan.library.Favourites
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.delay

/**
 * Whether a game's fan art, when it has one, is the backdrop instead of its blurred cover.
 * The reviewer's default; set it to false to always blur the cover.
 */
private const val FANART_BACKDROP = true

/** How far a screenshot is pulled down before letting go closes the full view. */
private val SWIPE_CLOSE_DISTANCE = 96.dp

/** How long the delete button waits for its second tap. */
private const val DELETE_ARMED_MS = 4_000L

/** The bottom screen while browsing: the focused game's artwork, details, history and actions. */
@Composable
fun GameDetailsScreen(
    viewModel: GameDetailsViewModel,
    onShowReader: () -> Unit,
    /** How the game's manual shows once opened: plainly, unless the screen's host looks its words up. */
    manualView: @Composable (file: File, onClose: (File, String?) -> Unit) -> Unit = { file, close -> ManualFile(file, onClose = close) },
) {
    val details by viewModel.details.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val manualFetch by viewModel.manualFetch.collectAsStateWithLifecycle()
    // Each game's details start at the top.
    val scroll = remember(details?.focus?.game?.id) { ScrollState(0) }
    GameDetailsContent(
        details = details,
        onShowReader = onShowReader,
        onPlay = viewModel::play,
        onOpenManual = viewModel::openManual,
        onDelete = viewModel::deleteDownload,
        onToggleFavourite = viewModel::toggleFavourite,
        onLook = viewModel::openLookPicker,
        notice = notice,
        manualFetch = manualFetch,
        factsScroll = scroll,
    )
    val pickingLook by viewModel.pickingLook.collectAsStateWithLifecycle()
    details?.look?.takeIf { pickingLook }?.let { look ->
        BottomBackHandler(onBack = viewModel::closeLookPicker)
        LookPicker(
            title = "${details!!.focus.game.name}: look",
            selected = look.own,
            console = look.console,
            suits = ScreenLook.suiting(details!!.focus.game.platformSlug),
            onPick = viewModel::pickLook,
            onClose = viewModel::closeLookPicker,
        )
    }
    val manual by viewModel.manual.collectAsStateWithLifecycle()
    manual?.let { file -> manualView(file, viewModel::closeManual) }
}


/**
 * The details drawn from plain state; null [details] is an empty screen. Touch-first, in two
 * columns: the artwork and the buttons on the left, everything to read on the right, which
 * scrolls with [factsScroll]. [initialScreenshot] opens one screenshot full-size, for the
 * screenshot tests.
 */
@Composable
fun GameDetailsContent(
    details: GameDetails?,
    onShowReader: () -> Unit,
    onPlay: () -> Unit = {},
    onOpenManual: () -> Unit = {},
    onDelete: () -> Unit = {},
    onToggleFavourite: () -> Unit = {},
    onLook: () -> Unit = {},
    notice: String? = null,
    /** The manual being fetched for "Open manual", or why it couldn't be. */
    manualFetch: ManualFetch? = null,
    initialScreenshot: Int? = null,
    factsScroll: ScrollState = rememberScrollState(),
    /** The art and buttons column's scroll, for a test to start it scrolled. */
    actionsScroll: ScrollState = rememberScrollState(),
) {
    val colors = MaterialTheme.colorScheme
    val current = details ?: return Box(Modifier.fillMaxSize().background(colors.background))
    val game = current.focus.game
    var viewing by remember(game.id) { mutableStateOf(initialScreenshot) }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Backdrop(current)
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    current.focus.platformName,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Chip("Backlog", onShowReader)
            }
            Row(
                Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ArtAndActions(current, notice, manualFetch, onPlay, onOpenManual, onDelete, onToggleFavourite, onLook, actionsScroll, Modifier.width(176.dp).fillMaxHeight())
                Facts(current, factsScroll, onScreenshot = { viewing = it }, Modifier.weight(1f).fillMaxHeight())
            }
        }
        viewing?.let { start ->
            ScreenshotViewer(current.art.screenshots, start, onClose = { viewing = null })
        }
    }
}

/** Fan art dimmed, or the cover blurred, under a scrim dark enough to read text over. */
@Composable
private fun Backdrop(details: GameDetails) {
    val background = MaterialTheme.colorScheme.background
    val fanart = details.art.fanart.takeIf { FANART_BACKDROP }
    when {
        fanart != null -> AsyncImage(
            model = fanart,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.4f),
        )
        details.focus.cover != null && details.focus.game.platformSlug != LocalShelves.APPS_SLUG -> AsyncImage(
            model = details.focus.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(36.dp).alpha(0.5f),
        )
        else -> return
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to background.copy(alpha = 0.25f), 0.45f to background.copy(alpha = 0.8f)),
        ),
    )
}

/** The logo, the disc or cartridge (the cover when there is none), and the buttons. */
@Composable
private fun ArtAndActions(
    details: GameDetails,
    notice: String?,
    manualFetch: ManualFetch?,
    onPlay: () -> Unit,
    onOpenManual: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavourite: () -> Unit,
    onLook: () -> Unit,
    scroll: ScrollState,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val game = details.focus.game
    val installed = details.download?.state == DownloadState.INSTALLED
    var deleteArmed by remember(game.id) { mutableStateOf(false) }
    LaunchedEffect(deleteArmed) {
        if (deleteArmed) {
            delay(DELETE_ARMED_MS)
            deleteArmed = false
        }
    }

    // The art gives way, down to ART_MIN, so the first three buttons fit whole; any after them
    // (the look, Delete) scroll, the column's edges fading while there's more that way.
    BoxWithConstraints(modifier) {
        val buttons = listOf(true, details.art.hasManual, Favourites.canMark(game), details.look != null, installed).count { it }
        val art = artSize(maxHeight, logo = details.art.logo != null, buttonsShown = minOf(buttons, 3), more = buttons > 3)
        Column(
            Modifier
                .fillMaxSize()
                .edgeFade(top = scroll.canScrollBackward, bottom = scroll.canScrollForward, height = FADE)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            details.art.logo?.let { logo ->
                AsyncImage(
                    model = logo,
                    contentDescription = game.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(LOGO_HEIGHT),
                )
            }
            Box(Modifier.size(art), contentAlignment = Alignment.Center) {
                val physical = details.art.physical
                val cover = details.focus.cover
                when {
                    physical != null -> AsyncImage(
                        model = physical,
                        contentDescription = game.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    cover != null -> AsyncImage(
                        model = cover,
                        contentDescription = game.name,
                        contentScale = if (game.platformSlug == LocalShelves.APPS_SLUG) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier.fillMaxHeight().aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)),
                    )
                    // No art at all: a plain card with the title, as the library shows it.
                    else -> Box(
                        Modifier.fillMaxHeight().aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)).background(colors.surface).padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(game.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            DetailsButton(GameDetailsFormat.primaryLabel(game, details.download), ButtonStyle.PRIMARY, onPlay)
            if (details.art.hasManual) {
                // A fetch shows on the button itself, in view without scrolling (it's one of the first three).
                when (manualFetch) {
                    is ManualFetch.Busy -> BusyDetailsButton(
                        manualFetch.progress?.let { "Fetching… ${(it * 100).toInt()}%" } ?: "Fetching the manual…",
                        ButtonStyle.PLAIN, manualFetch.progress, isBusy = true, onClick = onOpenManual,
                    )
                    is ManualFetch.Failed -> DetailsButton(manualFetch.message, ButtonStyle.DANGER, onOpenManual)
                    null -> DetailsButton("Open manual", ButtonStyle.PLAIN, onOpenManual)
                }
            }
            if (Favourites.canMark(game)) {
                DetailsButton(if (details.favourite) "Remove from favourites" else "Add to favourites", ButtonStyle.PLAIN, onToggleFavourite)
            }
            details.look?.let { look ->
                DetailsButton(GameDetailsFormat.lookLabel(look), ButtonStyle.PLAIN, onLook)
            }
            if (installed) {
                DetailsButton(if (deleteArmed) "Tap again to delete" else "Delete download", ButtonStyle.DANGER) {
                    if (deleteArmed) {
                        deleteArmed = false
                        onDelete()
                    } else {
                        deleteArmed = true
                    }
                }
            }
            notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

private val GAP = 8.dp
private val LOGO_HEIGHT = 64.dp
private val BUTTON_HEIGHT = 44.dp
private val ART_MAX = 176.dp
private val ART_MIN = 112.dp
private val FADE = 20.dp

/**
 * The art's size in a column [height] tall: as big as leaves room for the logo (if any) and the
 * first [buttonsShown] buttons, between [ART_MIN] and [ART_MAX]. With [more] buttons after them it
 * also leaves a gap and the fade, so the next one shows fading out below and the list reads as going on.
 */
internal fun artSize(height: Dp, logo: Boolean, buttonsShown: Int, more: Boolean = false): Dp {
    val above = if (logo) LOGO_HEIGHT + GAP else 0.dp
    // The art, a gap, the 4 dp spacer, a gap, then the buttons with gaps between them.
    val below = GAP + 4.dp + GAP + BUTTON_HEIGHT * buttonsShown + GAP * (buttonsShown - 1).coerceAtLeast(0) +
        (if (more) GAP + FADE else 0.dp)
    return (height - above - below).coerceIn(ART_MIN, ART_MAX)
}

private enum class ButtonStyle { PRIMARY, PLAIN, DANGER }

/** A full-width button sized for a thumb. */
@Composable
private fun DetailsButton(label: String, style: ButtonStyle, onClick: () -> Unit) = BusyDetailsButton(label, style, null, false, onClick)

/** A details button that, while [isBusy], shows a ring filled to [progress] (or turning, unknown) and takes no presses. */
@Composable
private fun BusyDetailsButton(label: String, style: ButtonStyle, progress: Float?, isBusy: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val (fill, text) = when (style) {
        ButtonStyle.PRIMARY -> colors.primary to colors.onPrimary
        ButtonStyle.PLAIN -> colors.surface.copy(alpha = 0.85f) to colors.onSurface
        ButtonStyle.DANGER -> colors.surface.copy(alpha = 0.85f) to colors.error
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(BUTTON_HEIGHT)
            .clip(shape)
            .background(fill)
            .border(1.dp, if (style == ButtonStyle.DANGER) colors.error.copy(alpha = 0.5f) else Color.Transparent, shape)
            // Busy, it takes no presses: one fetch at a time.
            .clickable(enabled = !isBusy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isBusy) BusyRing(progress, text)
            Text(label, style = MaterialTheme.typography.labelLarge, color = text, maxLines = 1)
        }
    }
}

/** The title, what the game is, what you've done with it, its summary and its screenshots. */
@Composable
private fun Facts(details: GameDetails, scroll: ScrollState, onScreenshot: (Int) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val game = details.focus.game
    val played = listOfNotNull(
        GameDetailsFormat.downloadLine(game, details.download),
        GameDetailsFormat.playLine(details.play, game.lastPlayedMillis),
    )
    val hltb = GameDetailsFormat.hltbLine(game.hltbMainStorySec)
    val hltbMore = GameDetailsFormat.hltbMoreLine(game.hltbMainPlusExtraSec, game.hltbCompletionistSec)
    val hltbProgress = GameDetailsFormat.hltbProgress(game.hltbMainStorySec, details.play?.totalMs ?: 0)
    val rest = listOfNotNull(
        GameDetailsFormat.rommLine(game),
        GameDetailsFormat.readingLine(details.reading),
        GameDetailsFormat.lookupLine(details.lookups),
        GameDetailsFormat.saveLine(details.save),
    )
    val line = @Composable { text: String -> Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface) }

    Box(modifier.edgeFade(top = scroll.value > 0)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(game.name, style = MaterialTheme.typography.headlineSmall, color = colors.primary)
            GameDetailsFormat.detailLine(game)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            // How this build reads the game's text, once it has run and the catalog knows it.
            details.textSource?.let { source ->
                Text(
                    source.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.primary.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            if (played.isNotEmpty() || hltb != null || hltbMore != null || rest.isNotEmpty()) {
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    played.forEach { line(it) }
                    if (hltb != null || hltbMore != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            hltb?.let { line(it) }
                            hltbProgress?.let { progress -> ProgressBar(progress) }
                            hltbMore?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                    rest.forEach { line(it) }
                }
            }
            game.summary?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.padding(top = 6.dp))
            }
            if (details.art.screenshots.isNotEmpty()) {
                LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(details.art.screenshots) { index, shot ->
                        AsyncImage(
                            model = shot,
                            contentDescription = "Screenshot ${index + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .height(80.dp)
                                .aspectRatio(4f / 3f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onScreenshot(index) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Fades the content's [top] or [bottom] [height] out to nothing, so a scrolled list melts into
 * whatever backdrop is behind it instead of being cut off, and reads as going on.
 */
private fun Modifier.edgeFade(top: Boolean, bottom: Boolean = false, height: Dp = 20.dp): Modifier = if (!top && !bottom) this else
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val fade = height.toPx()
        if (top) {
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = fade), blendMode = BlendMode.DstIn)
        }
        if (bottom) {
            drawRect(
                Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - fade, endY = size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** A thin bar filled to [progress] (0 to 1), e.g. the time played against HowLongToBeat's. */
@Composable
private fun ProgressBar(progress: Float) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Box(Modifier.fillMaxWidth().height(4.dp).clip(shape).background(colors.onSurface.copy(alpha = 0.15f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(colors.primary))
    }
}

/**
 * Screenshots over the whole screen, one at a time: swipe sideways between them. A tap on the
 * picture, the Close button, a swipe down or Back closes it.
 */
@Composable
private fun ScreenshotViewer(screenshots: List<Any>, start: Int, onClose: () -> Unit) {
    BottomBackHandler(onBack = onClose)
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (screenshots.size - 1).coerceAtLeast(0))) { screenshots.size }
    // How far the screenshot has been pulled down; past the threshold, letting go closes it.
    var pulled by remember { mutableFloatStateOf(0f) }
    val closeAfter = with(LocalDensity.current) { SWIPE_CLOSE_DISTANCE.toPx() }
    val close by rememberUpdatedState(onClose)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Only a vertical drag gets here: the pager keeps horizontal swipes for itself.
            .pointerInput(closeAfter) {
                detectVerticalDragGestures(
                    onDragEnd = { if (pulled > closeAfter) close() else pulled = 0f },
                    onDragCancel = { pulled = 0f },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        pulled = (pulled + dy).coerceAtLeast(0f)
                    },
                )
            },
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize().graphicsLayer { translationY = pulled }) { page ->
            AsyncImage(
                model = screenshots[page],
                contentDescription = "Screenshot ${page + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clickable(onClick = onClose),
            )
        }
        Text(
            "${pager.currentPage + 1} / ${screenshots.size}",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
        Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            Chip("Close", onClose)
        }
    }
}
