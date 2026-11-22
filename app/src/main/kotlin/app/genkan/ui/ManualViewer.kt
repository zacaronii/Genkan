package app.genkan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.manual.PlayManual
import app.genkan.ocr.ScreenText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The most a manual page can be zoomed. */
private const val MAX_ZOOM = 4f

/** Past this zoom, pages are drawn again at twice the screen's width so text stays sharp. */
private const val SHARP_ZOOM = 1.5f

/** How far the manual is pulled down from its first page before letting go closes it. */
private val PULL_CLOSE_DISTANCE = 96.dp


/**
 * Extra space under the last page while zoomed, in pixels. The list is scaled from its top
 * edge, so at [zoom] only the top 1 / [zoom] of what it lays out is on screen; without this,
 * scrolling stops while the end of the last page is still below the screen.
 */
fun zoomedEndPadding(viewportHeightPx: Int, zoom: Float): Float =
    viewportHeightPx * (1f - 1f / zoom.coerceAtLeast(1f))

/**
 * Keeps what is under the fingers there while a pinch changes the zoom. The pages are scaled
 * about the middle of their top edge, so sideways the page moves with [shift], and up and down
 * the list scrolls.
 */
internal object PinchFocus {
    /** The sideways shift that keeps screen x [focusX] over the same point when the zoom goes from [zoom] to [next]. */
    fun shift(shift: Float, zoom: Float, next: Float, focusX: Float, centreX: Float): Float =
        focusX - centreX - (focusX - centreX - shift) * next / zoom

    /** How far to scroll the list (in its own, unscaled pixels) to keep screen y [focusY] over the same point. */
    fun scroll(zoom: Float, next: Float, focusY: Float): Float = focusY / zoom - focusY / next
}

/**
 * A game's manual over the whole bottom screen: its pages one under the other, as wide as the
 * screen, scrolled with a finger. Pinch or double-tap zooms; "page n / N" shows where you are;
 * the Close button, Back, or pulling down past the first page closes it. With [onTapText], a
 * single tap on a page reports where (its index, the tap as fractions of the page, and the
 * unzoomed page width), for a word to be looked up there; [status] says how that's going, in the
 * bar, and [mark] boxes the word on its page.
 *
 * Only the pages on screen (and the one or two the list lays out ahead) have bitmaps; each is
 * drawn off the main thread when its page comes into view and recycled when it leaves.
 */
@Composable
fun ManualViewer(
    pages: ManualPages,
    onClose: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    /** The close button's words: "Close" in the details, "Back to reader" during play. */
    closeLabel: String = "Close",
    onTapText: ((page: Int, x: Float, y: Float, widthPx: Int) -> Unit)? = null,
    status: String? = null,
    mark: ManualMark? = null,
) {
    BottomBackHandler(onBack = onClose)
    val close by rememberUpdatedState(onClose)
    val closeAfter = with(LocalDensity.current) { PULL_CLOSE_DISTANCE.toPx() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var shiftX by remember { mutableFloatStateOf(0f) }
    var pulled by remember { mutableFloatStateOf(0f) }
    // The page across the middle of the screen is the one being read.
    val shownPage by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val middle = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo.firstOrNull { it.offset <= middle && it.offset + it.size > middle }?.index
                ?: listState.firstVisibleItemIndex
        }
    }
    // Bitmaps are recycled from here, a frame or two after they leave the screen.
    val recycler = rememberCoroutineScope()

    // Pulling down at the first page moves the manual down; letting go far enough closes it.
    val pull = remember(closeAfter) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Pushing back up first undoes the pull.
                if (pulled <= 0f || available.y >= 0f) return Offset.Zero
                val used = maxOf(available.y, -pulled)
                pulled += used
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f || zoom > 1f) return Offset.Zero
                pulled += available.y
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pulled <= 0f) return Velocity.Zero
                if (pulled > closeAfter) close() else pulled = 0f
                return available
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF2A2A30))) {
        // A bar of its own, so where you are and the way out never sit over a page.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            status?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
            }
            Text(
                "page ${shownPage + 1} / ${pages.pageSizes.size}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .background(Color(0xCC000000), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
            Chip(closeLabel, onClose)
        }
        // Clipped, so a zoomed or pulled page stays under the bar.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            val widthPx = constraints.maxWidth
            val renderWidth = if (zoom > SHARP_ZOOM) widthPx * 2 else widthPx
            val endPadding = with(LocalDensity.current) { zoomedEndPadding(constraints.maxHeight, zoom).toDp() }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + endPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(pull)
                    .pointerInput(widthPx) {
                        // Two fingers zoom and pan; while zoomed, one finger's sideways movement pans
                        // too. Seen before the list, so a pinch never scrolls it; one finger's vertical
                        // movement is left to the list.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pinch = event.changes.count { it.pressed } >= 2
                                if (pinch) {
                                    // Zoom about the fingers: what is under them stays under them.
                                    val next = (zoom * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                    val focus = event.calculateCentroid()
                                    if (next != zoom && focus.isSpecified) {
                                        shiftX = PinchFocus.shift(shiftX, zoom, next, focus.x, widthPx / 2f)
                                        listState.dispatchRawDelta(PinchFocus.scroll(zoom, next, focus.y))
                                    }
                                    zoom = next
                                }
                                // A zoomed page can move sideways until its edge meets the screen's.
                                val limit = widthPx * (zoom - 1f) / 2f
                                val dx = if (pinch) event.calculatePan().x else event.changes.first().positionChange().x
                                shiftX = (shiftX + dx).coerceIn(-limit, limit)
                                if (pinch) event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = shiftX
                        translationY = pulled
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    },
            ) {
                items(pages.pageSizes.size) { index ->
                    val size = pages.pageSizes[index]
                    ManualPage(
                        pages, index, size.width.toFloat() / size.height.coerceAtLeast(1), renderWidth, recycler,
                        onTap = onTapText?.let { tap -> { x, y -> tap(index, x, y, widthPx) } },
                        onDoubleTap = {
                            zoom = if (zoom > 1f) 1f else 2f
                            shiftX = 0f
                        },
                        mark = mark?.takeIf { it.page == index }?.box,
                    )
                }
            }
        }
    }
}

/** A word's box on a manual page: [box] as fractions of page [page]. */
data class ManualMark(val page: Int, val box: ScreenText.Box)

/**
 * One page: white until its bitmap is drawn, then the bitmap at [renderWidth] pixels wide. Taps
 * here are in the page's own coordinates, whatever the list's zoom: [onTap] gets a single tap as
 * fractions of the page, and [mark] is boxed over it.
 */
@Composable
private fun ManualPage(
    pages: ManualPages,
    index: Int,
    aspect: Float,
    renderWidth: Int,
    recycler: CoroutineScope,
    onTap: ((x: Float, y: Float) -> Unit)?,
    onDoubleTap: () -> Unit,
    mark: ScreenText.Box?,
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(renderWidth) {
        val fresh = pages.render(index, renderWidth) ?: return@LaunchedEffect
        val old = bitmap
        bitmap = fresh
        old?.let { recycleLater(it, recycler) }
    }
    DisposableEffect(Unit) {
        onDispose { bitmap?.let { recycleLater(it, recycler) } }
    }
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val markColor = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.1f, 10f))
            .background(Color.White)
            // A single tap waits out a possible second one only where taps look words up.
            .pointerInput(onTap != null) {
                detectTapGestures(
                    onTap = if (onTap == null) null else { at -> tap?.invoke(at.x / size.width, at.y / size.height) },
                    onDoubleTap = { doubleTap() },
                )
            }
            .drawWithContent {
                drawContent()
                mark?.let { box ->
                    val topLeft = Offset(box.left * size.width, box.top * size.height)
                    val boxSize = Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height)
                    drawRect(markColor.copy(alpha = 0.3f), topLeft, boxSize)
                    drawRect(markColor, topLeft, boxSize, style = Stroke(width = 2.dp.toPx()))
                }
            },
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = "Page ${index + 1}", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Recycles [bitmap] once the frames that may still draw it have gone by. If the viewer closes
 * first, [scope] is cancelled and the bitmap is left to the garbage collector.
 */
private fun recycleLater(bitmap: Bitmap, scope: CoroutineScope) {
    scope.launch {
        withFrameNanos {}
        withFrameNanos {}
        bitmap.recycle()
    }
}

/**
 * Opens [file] with [PdfManualPages] for [ManualViewer], and closes it again when the viewer
 * goes. A file that won't open closes the viewer with a notice. [onClose] is always told which
 * file it's about, so a failure that reports after another manual opened names the old one.
 */
@Composable
internal fun ManualFile(
    file: File,
    onClose: (file: File, problem: String?) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    closeLabel: String = "Close",
    /** A single tap on a page of [file] (see [ManualViewer]); null leaves single taps alone. */
    onTapText: ((file: File, pages: ManualPages, page: Int, x: Float, y: Float, widthPx: Int) -> Unit)? = null,
    status: String? = null,
    mark: ManualMark? = null,
) {
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
        else -> ManualViewer(
            open,
            onClose = { close(file, null) },
            listState = listState,
            closeLabel = closeLabel,
            onTapText = onTapText?.let { tap -> { page, x, y, width -> tap(file, open, page, x, y, width) } },
            status = status,
            mark = mark,
        )
    }
}

/**
 * The playing game's manual over the reader while [manual] has it open, at the page it was left
 * at; Back to reader (or Back) closes it and keeps that page. A tap on a page looks the word
 * there up through [reader]: the word is boxed on its page and the lookup's panel opens over the
 * manual's lower part, as it does over the reader.
 */
@Composable
fun PlayManualLayer(manual: PlayManual, reader: ReaderViewModel) {
    val open by manual.open.collectAsStateWithLifecycle()
    val file = open ?: return
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = manual.pageOf(file))
    val status by reader.manualStatus.collectAsStateWithLifecycle()
    val hit by reader.manualHit.collectAsStateWithLifecycle()
    val lookup by reader.lookup.collectAsStateWithLifecycle()
    val popup by reader.popup.collectAsStateWithLifecycle()
    val manualLookup = lookup?.takeIf { it.lineId == MANUAL_LINE }
    // The box covers the longest match once the dictionaries answer, the tapped character until then.
    val mark = hit?.let { h ->
        val l = manualLookup ?: return@let null
        h.hit.line?.let { line -> ManualMark(h.page, line.span(l.start, if (l.result != null && l.end > l.start) l.end else l.start + 1)) }
    }
    DisposableEffect(file) {
        onDispose {
            manual.keepPage(file, listState.firstVisibleItemIndex)
            reader.manualClosed()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        ManualFile(
            file,
            onClose = manual::closed,
            listState = listState,
            closeLabel = "Back to reader",
            onTapText = reader::tapManual,
            status = status,
            mark = mark,
        )
        manualLookup?.let { current ->
            LookupPanel(
                current,
                popup,
                reader,
                onClose = reader::closeLookup,
                modifier = Modifier.align(Alignment.BottomCenter).height(maxHeight * MANUAL_PANEL_FRACTION),
            )
        }
    }
}

/** Share of the manual the lookup's panel takes: as over the reader, leaving the word's line in view above it. */
private const val MANUAL_PANEL_FRACTION = 0.55f
