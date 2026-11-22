package app.genkan.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import app.genkan.download.DownloadState
import app.genkan.launch.LocalShelves
import app.genkan.library.Favourites
import coil3.compose.AsyncImage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

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
fun GameDetailsScreen(viewModel: GameDetailsViewModel, onShowReader: () -> Unit) {
    val details by viewModel.details.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    // Each game's details start at the top.
    val scroll = remember(details?.focus?.game?.id) { ScrollState(0) }
    GameDetailsContent(
        details = details,
        onShowReader = onShowReader,
        onPlay = viewModel::play,
        onOpenManual = viewModel::openManual,
        onDelete = viewModel::deleteDownload,
        onToggleFavourite = viewModel::toggleFavourite,
        notice = notice,
        factsScroll = scroll,
    )
    val manual by viewModel.manual.collectAsStateWithLifecycle()
    manual?.let { file -> ManualFile(file, onClose = viewModel::closeManual) }
}

/**
 * Opens [file] with [PdfManualPages] for [ManualViewer], and closes it again when the viewer
 * goes. A file that won't open closes the viewer with a notice. [onClose] is always told which
 * file it's about, so a failure that reports after another manual opened names the old one.
 */
@Composable
private fun ManualFile(file: File, onClose: (file: File, problem: String?) -> Unit) {
    val close by rememberUpdatedState(onClose)
    var pages by remember(file) { mutableStateOf<ManualPages?>(null) }
    LaunchedEffect(file) {
        val opened = try {
            // Not cut short: an open that finishes as the viewer goes is closed just below.
            withContext(NonCancellable) { PdfManualPages.open(file) }
        } catch (e: Exception) {
            close(file, "Couldn't open the manual: ${e.message}")
            return@LaunchedEffect
        }
        if (isActive) pages = opened else opened.close()
    }
    DisposableEffect(file) {
        onDispose { pages?.close() }
    }
    when (val open = pages) {
        null -> Box(Modifier.fillMaxSize().background(Color(0xFF2A2A30)), contentAlignment = Alignment.Center) {
            Text("Opening the manual…", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
        else -> ManualViewer(open, onClose = { close(file, null) })
    }
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
    notice: String? = null,
    initialScreenshot: Int? = null,
    factsScroll: ScrollState = rememberScrollState(),
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
                ArtAndActions(current, notice, onPlay, onOpenManual, onDelete, onToggleFavourite, Modifier.width(176.dp).fillMaxHeight())
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
    onPlay: () -> Unit,
    onOpenManual: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavourite: () -> Unit,
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

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        details.art.logo?.let { logo ->
            AsyncImage(
                model = logo,
                contentDescription = game.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            )
        }
        Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
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
        if (details.art.hasManual) DetailsButton("Open manual", ButtonStyle.PLAIN, onOpenManual)
        if (Favourites.canMark(game)) {
            DetailsButton(if (details.favourite) "Remove from favourites" else "Add to favourites", ButtonStyle.PLAIN, onToggleFavourite)
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

private enum class ButtonStyle { PRIMARY, PLAIN, DANGER }

/** A full-width button sized for a thumb. */
@Composable
private fun DetailsButton(label: String, style: ButtonStyle, onClick: () -> Unit) {
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
            .height(44.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, if (style == ButtonStyle.DANGER) colors.error.copy(alpha = 0.5f) else Color.Transparent, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = text, maxLines = 1)
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

    Box(modifier.topFade(scroll.value > 0)) {
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
 * Once [enabled], fades the content's top [height] out to nothing, so scrolled text melts into
 * whatever backdrop is behind it instead of being cut off.
 */
private fun Modifier.topFade(enabled: Boolean, height: Dp = 20.dp): Modifier = if (!enabled) this else
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = height.toPx()),
            blendMode = BlendMode.DstIn,
        )
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
