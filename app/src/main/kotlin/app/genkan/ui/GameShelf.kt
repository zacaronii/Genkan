package app.genkan.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadState
import app.genkan.launch.LocalShelves
import app.genkan.library.CaseSpec
import app.genkan.library.CaseStyle
import app.genkan.library.GameCase
import app.genkan.library.GameEntity
import app.genkan.library.MediaKind
import coil3.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.delay

/**
 * The shelf's tunable numbers, the reviewer's defaults kept in one place. Distances are in
 * multiples of the console's usual case width, so DS cases and Super Famicom boxes space alike.
 */
internal object ShelfDefaults {
    /** How long the focused game rests before its disc or cartridge slides out. */
    const val MEDIA_REST_MS = 300L
    /** How long the slide takes. */
    const val MEDIA_SLIDE_MS = 420
    /** How much of the disc or cartridge shows once it is out, as a share of its own size. */
    const val MEDIA_PEEK = 0.5f
    /** The neighbours' size next to the focused case's. */
    const val NEIGHBOUR_SCALE = 0.8f
    /** How far the neighbours turn toward the middle, in degrees. */
    const val NEIGHBOUR_ANGLE = 50f
    /** How much darker the farthest visible neighbours are. */
    const val NEIGHBOUR_SHADE = 0.45f
    /** From the focused case's centre to its neighbours', in case widths. */
    const val SIDE_OFFSET = 0.68f
    /** Between one neighbour and the next further out, in case widths. */
    const val STACK_STEP = 0.27f
    /** Cases composed either side of the focused one; the rest of the shelf isn't drawn. */
    const val WINDOW = 7
    /** Cases this far out start to fade, and are gone at [WINDOW]. */
    const val FADE_FROM = 5f
    /**
     * How far out cases are drawn while a direction is held: they fade from one case nearer and are
     * gone here. The rest stay composed but at alpha 0, which costs the GPU nothing, and come back
     * when the shelf rests.
     */
    const val HOLD_REACH = 5f
    /** Steps closer together than this count as a held D-pad (key repeats are about 50 ms apart). */
    const val HOLD_GAP_MS = 110L
    /** How long the drawn reach takes to shrink or grow back. */
    const val REACH_MS = 180
    /** How far the focused case steps toward the viewer, down from the shelf's edge. */
    val STEP_FORWARD = 10.dp
    /** The tallest a case may be, as a share of the shelf's height (the rest is media and shadow). */
    const val CASE_HEIGHT = 0.7f
    /** The widest a case may be, as a share of the shelf's width. */
    const val CASE_WIDTH = 0.42f
}

/**
 * Where a case sits and how it turns for its distance from the focus, so the arithmetic can be
 * checked without drawing. [d] is the case's index minus the shelf's (animated) position:
 * 0 is focused, negative is to the left, and fractions happen mid-move.
 */
internal object ShelfGeometry {

    /**
     * The proportions the shelf's slots are sized for: those of the focused case's console (a
     * plain tile's, 1, for an app or PC game). On a shelf of one console that is every case's, so
     * the gaps stay as they were; on one of several the gap around the focus fits the case in it,
     * whichever case comes first.
     */
    fun slotAspect(games: List<GameEntity>, focused: Int): Float {
        val game = games.getOrNull(focused) ?: games.firstOrNull() ?: return 1f
        return if (LocalShelves.isLocal(game)) 1f else GameCase.specFor(game.platformSlug).aspect
    }

    /** Horizontal offset of the case's centre from the shelf's middle, in case widths. */
    fun offset(d: Float): Float {
        val ad = abs(d)
        val distance = if (ad <= 1f) ad * ShelfDefaults.SIDE_OFFSET else ShelfDefaults.SIDE_OFFSET + (ad - 1f) * ShelfDefaults.STACK_STEP
        return distance * sign(d)
    }

    /** 1 when focused, [ShelfDefaults.NEIGHBOUR_SCALE] from the first neighbour out. */
    fun scale(d: Float): Float = 1f - (1f - ShelfDefaults.NEIGHBOUR_SCALE) * abs(d).coerceAtMost(1f)

    /**
     * The turn about the vertical axis, in degrees. Cases on the left turn their front to the
     * right, toward the middle, which shows their spine edge; cases on the right turn the other way.
     */
    fun rotationY(d: Float): Float = -d.coerceIn(-1f, 1f) * ShelfDefaults.NEIGHBOUR_ANGLE

    /**
     * Fully opaque near the focus, fading out over the last two cases before [reach]: at rest that
     * is between [ShelfDefaults.FADE_FROM] and [ShelfDefaults.WINDOW], and while a direction is
     * held it ends at [ShelfDefaults.HOLD_REACH].
     */
    fun alpha(d: Float, reach: Float = ShelfDefaults.WINDOW.toFloat()): Float =
        ((reach - abs(d)) / (ShelfDefaults.WINDOW - ShelfDefaults.FADE_FROM)).coerceIn(0f, 1f)

    /** How much black lies over the case, for depth: none on the focused one. */
    fun shade(d: Float): Float = ShelfDefaults.NEIGHBOUR_SHADE * (abs(d) / ShelfDefaults.FADE_FROM).coerceAtMost(1f)

    /** How far the case steps forward, 1 when focused and 0 from the first neighbour out. */
    fun forward(d: Float): Float = 1f - abs(d).coerceAtMost(1f)

    /** Which cases are drawn around [focused] out of [count]. */
    fun window(focused: Int, count: Int): IntRange =
        if (count == 0) IntRange.EMPTY else (focused - ShelfDefaults.WINDOW).coerceAtLeast(0)..(focused + ShelfDefaults.WINDOW).coerceAtMost(count - 1)
}

/**
 * One console's games as a row of cases, face-out, with the focused one stepped forward and its
 * neighbours turned in like a crate of games.
 *
 * Moving along the shelf animates a single position. Only the placement (for the overlap order)
 * and each case's graphics layer read it, so a frame of the animation re-places and re-transforms
 * the cases without recomposing them; a new focus recomposes just the cases entering and leaving
 * [ShelfGeometry.window]. [active] is false while the console strip has the focus: the cases stay
 * put but no disc slides out. [staticMedia] draws the media fully out (true) or in (false)
 * without animating, for screenshots.
 */
@Composable
internal fun GameShelf(
    games: List<GameEntity>,
    focusedIndex: Int,
    active: Boolean,
    downloads: Map<Long, DownloadEntity>,
    coverModel: (GameEntity) -> Any?,
    artModel: (String?) -> Any?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    staticMedia: Boolean? = null,
    /** Which shelf this is, for what it remembers; "Continue playing" mixes consoles. */
    shelfId: Long = games.firstOrNull()?.platformId ?: 0L,
) {
    if (games.isEmpty()) return
    val focused = focusedIndex.coerceIn(0, games.lastIndex)
    val local = LocalShelves.isLocal(games.first())

    // Read once per shelf: whether to log what each step costs (see ShelfTrace).
    val trace = remember { ShelfTrace.enabled() }
    if (trace) SideEffect { ShelfTrace.shelfComposed(focused) }

    // Each cover's measured proportions, by game id, for the console's whole shelf, so a case
    // that leaves the window and comes back is the right width from its first frame. A plain map:
    // a cover loading mid-flight writes no state. The next step's recomposition, which happens
    // anyway, passes the new proportions down; [settled] picks up the ones that load once the shelf
    // stands still.
    val coverAspects = remember(shelfId) { HashMap<Long, Float>() }
    val settled = remember(shelfId) { mutableIntStateOf(0) }

    // A new console starts on its first case without sliding across from the old one's position.
    val position = remember(shelfId) { Animatable(focused.toFloat()) }
    LaunchedEffect(shelfId, focused) {
        position.animateTo(focused.toFloat(), spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
        settled.intValue++
    }
    val positionNow = remember(position) { { position.value } }

    // The slots are sized for the focused case's console, so on a shelf of several ("Continue
    // playing") the gap fits the case in view; it moves with the focus. One console's shelf keeps
    // one size, and nothing animates. Read only in placement, so a change costs a layout pass.
    val slotAspect = ShelfGeometry.slotAspect(games, focused)
    val slot = remember(shelfId) { Animatable(slotAspect) }
    LaunchedEffect(shelfId, slotAspect) {
        slot.animateTo(slotAspect, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
    }
    val slotNow = remember(slot) { { slot.value } }
    val onCoverAspect = remember(coverAspects, position) {
        { id: Long, aspect: Float ->
            val old = coverAspects.put(id, aspect)
            val changed = old == null || abs(old - aspect) > 0.005f
            if (trace && changed) ShelfTrace.aspectMeasured(stateWrite = !position.isRunning)
            // At rest nothing else recomposes the shelf, so ask for it; mid-flight the next step will.
            if (changed && !position.isRunning) settled.intValue++
        }
    }

    val media = remember(shelfId) { Animatable(0f) }
    LaunchedEffect(shelfId, focused, active, staticMedia) {
        if (staticMedia != null) {
            media.snapTo(if (staticMedia) 1f else 0f)
            return@LaunchedEffect
        }
        media.snapTo(0f)
        if (!active) return@LaunchedEffect
        delay(ShelfDefaults.MEDIA_REST_MS)
        media.animateTo(1f, tween(ShelfDefaults.MEDIA_SLIDE_MS, easing = FastOutSlowInEasing))
    }
    val mediaNow = remember(media) { { media.value } }

    // While a direction is held, only the cases near the focus are drawn: the far ones, mostly
    // hidden under their neighbours anyway, cost the GPU a turned layer each on every frame. A tap
    // never counts as holding, so the edges don't flicker on single steps.
    var holding by remember(shelfId) { mutableStateOf(false) }
    val lastStep = remember(shelfId) { longArrayOf(0L) }
    LaunchedEffect(shelfId, focused) {
        val now = System.nanoTime()
        if (now - lastStep[0] < ShelfDefaults.HOLD_GAP_MS * 1_000_000) holding = true
        lastStep[0] = now
        delay(ShelfDefaults.HOLD_GAP_MS)
        holding = false
    }
    val reach = animateFloatAsState(
        if (holding) ShelfDefaults.HOLD_REACH else ShelfDefaults.WINDOW.toFloat(),
        tween(ShelfDefaults.REACH_MS),
        label = "shelf reach",
    )

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxCaseHeight = maxHeight * ShelfDefaults.CASE_HEIGHT
        val maxCaseWidth = maxWidth * ShelfDefaults.CASE_WIDTH
        // The cases stand on a shelf edge a little above the bottom, leaving room for their shadows.
        val floorFromBottom = 22.dp
        val stepPx = with(density) { ShelfDefaults.STEP_FORWARD.toPx() }
        ShelfPlank(floorFromBottom)

        val range = ShelfGeometry.window(focused, games.size)
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                // Read here so a bump re-runs only this loop, where the proportions are passed down.
                settled.intValue
                for (index in range) {
                    val game = games[index]
                    key(game.id) {
                        val transform = Modifier.graphicsLayer {
                            val d = index - positionNow()
                            val s = ShelfGeometry.scale(d)
                            scaleX = s
                            scaleY = s
                            rotationY = ShelfGeometry.rotationY(d)
                            translationY = ShelfGeometry.forward(d) * stepPx
                            alpha = ShelfGeometry.alpha(d, reach.value)
                            // Fade by scaling each draw's alpha instead of compositing the case in an
                            // offscreen buffer first, which the default does for any alpha below 1.
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            cameraDistance = 14f * density.density
                        }
                        val select = Modifier
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index) }
                            // For accessibility (and device tests): each case is its game, the focused one selected.
                            .semantics {
                                contentDescription = game.name
                                selected = index == focused
                            }
                        // The models are built once per game. Coil's models are plain Any, which
                        // Compose compares by identity, so a fresh URL string on each step would
                        // recompose every case in the window.
                        val art = remember(game, coverModel, artModel) {
                            CaseArt(
                                cover = artModel(game.coverLargePath) ?: coverModel(game),
                                spine = artModel(game.box2dSidePath),
                                logo = artModel(game.logoPath),
                                physical = artModel(game.physicalPath),
                            )
                        }
                        if (local) {
                            ShelfTile(game, coverModel(game), maxCaseWidth, maxCaseHeight, transform.then(select))
                        } else {
                            ShelfCase(
                                game = game,
                                // Each its own console's case, for a shelf of several consoles.
                                spec = GameCase.specFor(game.platformSlug),
                                maxWidth = maxCaseWidth,
                                maxHeight = maxCaseHeight,
                                art = art,
                                download = downloads[game.id],
                                coverAspect = coverAspects[game.id],
                                onCoverAspect = onCoverAspect,
                                showMedia = index == focused && active,
                                badgeOnLeft = index < focused,
                                raised = index == focused,
                                mediaOut = mediaNow,
                                shade = { ShelfGeometry.shade(index - positionNow()) },
                                modifier = transform.then(select),
                                trace = trace,
                            )
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(Constraints()) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                // Read here, the position re-places the cases each frame without recomposing them.
                val p = positionNow()
                val centre = constraints.maxWidth / 2f
                val nominalWidth = GameCase.fit(slotNow(), maxCaseWidth.toPx(), maxCaseHeight.toPx()).first
                val floor = constraints.maxHeight - floorFromBottom.roundToPx()
                placeables.forEachIndexed { i, placeable ->
                    val d = range.first + i - p
                    val x = centre + ShelfGeometry.offset(d) * nominalWidth - placeable.width / 2f
                    placeable.place(x.toInt(), floor - placeable.height, zIndex = -abs(d))
                }
            }
        }
    }
}

/** The shelf's front edge: a dark board the cases stand on, with a lit lip. */
@Composable
private fun ShelfPlank(fromBottom: Dp) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            val top = size.height - fromBottom.toPx()
            drawRect(
                Brush.verticalGradient(listOf(Color(0xFF26262D), Color(0xFF141418)), startY = top, endY = size.height),
                topLeft = Offset(0f, top),
                size = Size(size.width, size.height - top),
            )
            drawRect(Color(0x33FFFFFF), topLeft = Offset(0f, top), size = Size(size.width, 1.dp.toPx()))
        },
    )
}

/** An app or PC game: a plain tile with its icon or Steam art, no case. */
@Composable
private fun ShelfTile(game: GameEntity, cover: Any?, maxWidth: Dp, maxHeight: Dp, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val isApp = game.platformSlug == LocalShelves.APPS_SLUG
    val side = min(maxWidth, maxHeight)
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.size(if (isApp) side else side * 0.75f, side).clip(shape).background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(game.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
        if (cover != null) {
            AsyncImage(
                model = cover,
                // The tile is titled (GameShelf's semantics): its cover is decoration.
                contentDescription = null,
                contentScale = if (isApp) ContentScale.Fit else ContentScale.Crop,
                modifier = if (isApp) Modifier.fillMaxSize().padding(36.dp) else Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * What Coil loads for one case: the cover, the spine, the title logo and the disc or cartridge
 * cut-out, each null when the game has none. Compared by value, so a case whose art hasn't
 * changed skips recomposition.
 */
@Immutable
private data class CaseArt(val cover: Any?, val spine: Any?, val logo: Any?, val physical: Any?)

/** The proportions of a case's parts, as shares of its width, per [CaseStyle]. */
private class CaseParts(val corner: Float, val spine: Float, val frame: Float, val plastic: Boolean)

private fun partsOf(style: CaseStyle) = when (style) {
    CaseStyle.KEEP_CASE -> CaseParts(corner = 0.035f, spine = 0.06f, frame = 0.022f, plastic = true)
    CaseStyle.JEWEL_CASE -> CaseParts(corner = 0.012f, spine = 0.085f, frame = 0.018f, plastic = true)
    CaseStyle.CARD_CASE -> CaseParts(corner = 0.06f, spine = 0.05f, frame = 0.035f, plastic = true)
    CaseStyle.CARDBOARD_BOX -> CaseParts(corner = 0.006f, spine = 0.035f, frame = 0f, plastic = false)
}

/**
 * One game's case: its console's shape, a spine down the left, the cover behind the plastic, and
 * the download state on top. The case takes the real cover's proportions ([coverAspect], kept by
 * the shelf) once it has loaded. On
 * the focused game ([showMedia]) the disc or cartridge sits behind the case and slides out as
 * [mediaOut] goes from 0 to 1.
 */
@Composable
private fun ShelfCase(
    game: GameEntity,
    spec: CaseSpec,
    maxWidth: Dp,
    maxHeight: Dp,
    art: CaseArt,
    download: DownloadEntity?,
    coverAspect: Float?,
    onCoverAspect: (Long, Float) -> Unit,
    showMedia: Boolean,
    badgeOnLeft: Boolean,
    raised: Boolean,
    mediaOut: () -> Float,
    shade: () -> Float,
    modifier: Modifier,
    trace: Boolean = false,
) {
    if (trace) SideEffect { ShelfTrace.caseComposed() }
    val density = LocalDensity.current
    val aspect = GameCase.aspectFor(spec, coverAspect)
    val (width, height) = with(density) {
        val (w, h) = GameCase.fit(aspect, maxWidth.toPx(), maxHeight.toPx())
        w.toDp() to h.toDp()
    }
    val parts = partsOf(spec.style)
    val shape = RoundedCornerShape(width * parts.corner)
    val caseColor = Color(spec.color)

    Box(
        modifier
            .size(width, height)
            .drawBehind { drawFloorShadow() },
    ) {
        if (showMedia) PhysicalMedia(MediaKind.forSlug(game.platformSlug), art.physical, art.logo, game.name, caseColor, width, height, mediaOut)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.shape = shape
                    // A real shadow only under the focused case: one per rotated layer cost the GPU
                    // on every frame of a move. The others stand on their drawn floor shadow.
                    shadowElevation = if (raised) 6.dp.toPx() else 0f
                    clip = true
                }
                .background(caseColor),
        ) {
            Row(Modifier.fillMaxSize()) {
                Spine(spec.style, art.spine, caseColor, Modifier.width(width * parts.spine).fillMaxHeight())
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(
                            start = width * parts.frame * 0.5f,
                            end = width * parts.frame,
                            top = if (spec.style == CaseStyle.CARD_CASE) height * 0.06f else width * parts.frame,
                            bottom = width * parts.frame,
                        )
                        // A plain rectangle clip: a rounded one is a path clip, redone for every
                        // turned case on every frame, for corners a few pixels across.
                        .clipToBounds()
                        .background(caseColor),
                    contentAlignment = Alignment.Center,
                ) {
                    if (art.cover != null) {
                        // The title shows until the cover has loaded, and stays if it never does.
                        // Plain text under the image: no request, no state to flip on load.
                        CaseTitle(game.name, caseColor)
                        // Coil decodes at this box's laid-out size, the cover as the focused case
                        // draws it, never the full-size file. The neighbours draw the same bitmap
                        // at 0.8 through their graphics layer: decoding them smaller would mean a
                        // second request for every case as it passes through the focus.
                        AsyncImage(
                            model = art.cover,
                            // The case is titled (GameShelf's semantics): its cover is decoration.
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            onSuccess = { state ->
                                val image = state.result.image
                                if (trace) ShelfTrace.coverLoaded(state.result.dataSource.name)
                                if (image.width > 0 && image.height > 0) onCoverAspect(game.id, image.width.toFloat() / image.height)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        BlankCover(game.name, art.logo, caseColor)
                    }
                }
            }
            if (parts.plastic) PlasticSheen()
            else CardboardEdge()
            DownloadMark(game, download, badgeOnLeft)
            // Depth: the further from the focus, the darker. Its own layer, so only its alpha changes per frame.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = shade()
                        // One flat rectangle: nothing overlaps, so no offscreen buffer is needed.
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .background(Color.Black),
            )
        }
    }
}

/** A soft shadow on the shelf under the case. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFloorShadow() {
    val w = size.width
    val shadowHeight = 18.dp.toPx()
    drawOval(
        Brush.radialGradient(
            listOf(Color(0x99000000), Color.Transparent),
            center = Offset(w / 2f, size.height),
            radius = w * 0.62f,
        ),
        topLeft = Offset(-w * 0.08f, size.height - shadowHeight / 2f),
        size = Size(w * 1.16f, shadowHeight),
    )
}

/** The left edge: the server's spine image when there is one, else a darker strip drawn per case. */
@Composable
private fun Spine(style: CaseStyle, spine: Any?, caseColor: Color, modifier: Modifier) {
    Box(modifier.background(lerpColor(caseColor, Color.Black, 0.35f))) {
        if (spine != null) {
            AsyncImage(model = spine, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Canvas(Modifier.fillMaxSize()) {
            val line = 1.dp.toPx()
            // A lit edge where the spine turns into the front.
            drawRect(Color(0x40FFFFFF), topLeft = Offset(size.width - line, 0f), size = Size(line, size.height))
            if (style == CaseStyle.JEWEL_CASE && spine == null) {
                // The jewel case's hinge: ridged plastic at the top and bottom.
                val ridge = size.height * 0.012f
                for (i in 0 until 6) {
                    val y = size.height * 0.04f + i * ridge * 2f
                    drawRect(Color(0x30FFFFFF), topLeft = Offset(0f, y), size = Size(size.width, ridge))
                    drawRect(Color(0x30FFFFFF), topLeft = Offset(0f, size.height - y - ridge), size = Size(size.width, ridge))
                }
            }
        }
    }
}

/** Light catching the clear plastic sleeve across the top left. */
@Composable
private fun PlasticSheen() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(
                0f to Color(0x2EFFFFFF),
                0.35f to Color(0x08FFFFFF),
                0.36f to Color.Transparent,
            ),
        ),
    )
}

/** A cardboard box's printed edge: a pale top fold. */
@Composable
private fun CardboardEdge() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(0x33FFFFFF), size = Size(size.width, 1.5.dp.toPx()))
        drawRect(Color(0x26000000), topLeft = Offset(0f, size.height - 1.5.dp.toPx()), size = Size(size.width, 1.5.dp.toPx()))
    }
}

/** The title on a case whose cover hasn't loaded, in ink that reads on the case's colour. */
@Composable
private fun CaseTitle(title: String, caseColor: Color) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = if (caseColor.luminance() > 0.5f) Color(0xFF1C1C21) else Color(0xFFEDEDED),
        textAlign = TextAlign.Center,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(10.dp),
    )
}

/** No cover: the case's own colour with the title logo, or failing that the title. */
@Composable
private fun BlankCover(title: String, logo: Any?, caseColor: Color) {
    val ink = if (caseColor.luminance() > 0.5f) Color(0xFF1C1C21) else Color(0xFFEDEDED)
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerpColor(caseColor, Color.White, 0.08f), lerpColor(caseColor, Color.Black, 0.15f)))),
        contentAlignment = Alignment.Center,
    ) {
        LogoOrTitle(logo, title, ink, MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), Modifier.fillMaxSize().padding(12.dp))
    }
}

/**
 * The download state on the case: dimmed with a download arrow when the game isn't on the device,
 * a progress ring while it downloads, a red mark when the download failed. A game on the device
 * shows nothing.
 */
@Composable
private fun DownloadMark(game: GameEntity, download: DownloadEntity?, onLeft: Boolean) {
    if (LocalShelves.isLocal(game)) return
    val colors = MaterialTheme.colorScheme
    when (download?.state) {
        DownloadState.INSTALLED -> Unit
        null -> {
            Box(Modifier.fillMaxSize().background(Color(0x59000000)))
            Badge(Color(0xCC1C1C21), onLeft) {
                Canvas(Modifier.size(14.dp)) {
                    val stroke = 2.dp.toPx()
                    val c = size.width / 2f
                    drawLine(Color.White, Offset(c, 0f), Offset(c, size.height * 0.72f), stroke)
                    drawLine(Color.White, Offset(c - size.width * 0.32f, size.height * 0.42f), Offset(c, size.height * 0.74f), stroke)
                    drawLine(Color.White, Offset(c + size.width * 0.32f, size.height * 0.42f), Offset(c, size.height * 0.74f), stroke)
                    drawLine(Color.White, Offset(0f, size.height), Offset(size.width, size.height), stroke)
                }
            }
        }
        DownloadState.QUEUED, DownloadState.RUNNING -> {
            Box(Modifier.fillMaxSize().background(Color(0x40000000)))
            val fraction = if (download.bytesTotal > 0) download.bytesDone.toFloat() / download.bytesTotal else 0f
            Badge(Color(0xCC1C1C21), onLeft) {
                Canvas(Modifier.size(18.dp)) {
                    val stroke = Stroke(width = 3.dp.toPx())
                    drawArc(Color(0x40FFFFFF), 0f, 360f, useCenter = false, style = stroke)
                    drawArc(colors.primary, -90f, 360f * fraction, useCenter = false, style = stroke)
                }
            }
        }
        DownloadState.FAILED -> Badge(Color(0xE6B3261E), onLeft) {
            Text("!", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        }
    }
}

/**
 * A round badge in a bottom corner of the case: the left one on cases left of the focus, whose
 * right side is tucked under their neighbour, and the right one elsewhere.
 */
@Composable
private fun Badge(background: Color, onLeft: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = if (onLeft) Alignment.BottomStart else Alignment.BottomEnd) {
        Box(
            Modifier.size(28.dp).background(background, CircleShape).border(1.dp, Color(0x40FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/**
 * The disc or cartridge behind the focused case, drawn at its real size against the case and
 * slid out (right for discs, up for cartridges and cards) by [out], 0 to 1, read only in the
 * graphics layer. The server's cut-out is used when there is one, else a drawn one with the
 * logo (or title) as its label.
 */
@Composable
private fun BoxScope.PhysicalMedia(
    kind: MediaKind,
    model: Any?,
    logo: Any?,
    title: String,
    caseColor: Color,
    caseWidth: Dp,
    caseHeight: Dp,
    out: () -> Float,
) {
    val shape = mediaShape(kind, caseWidth, caseHeight)
    val density = LocalDensity.current
    // Starts centred behind the case; ends with MEDIA_PEEK of it past the case's edge.
    val (endX, endY) = with(density) {
        if (shape.slidesRight) {
            val start = (caseWidth - shape.width) / 2
            (caseWidth + shape.width * ShelfDefaults.MEDIA_PEEK - shape.width - start).toPx() to 0f
        } else {
            val start = (caseHeight - shape.height) / 2
            0f to (-shape.height * ShelfDefaults.MEDIA_PEEK - start).toPx()
        }
    }
    Box(
        Modifier
            .align(Alignment.Center)
            .size(shape.width, shape.height)
            .graphicsLayer {
                val t = out()
                translationX = endX * t
                translationY = endY * t
                alpha = if (t > 0f) 1f else 0f
            },
        contentAlignment = Alignment.Center,
    ) {
        MediaArt(kind, model, logo, title, caseColor, shape.outline)
    }
}

/**
 * A disc or cartridge filling its box: the server's cut-out ([model]) when there is one, else a
 * drawn one with the logo (or the title) on its label. Shared by the shelf and the insert animation.
 */
@Composable
internal fun MediaArt(kind: MediaKind, model: Any?, logo: Any?, title: String, caseColor: Color, outline: Shape) {
    // Clipped to the medium's outline, so nothing drawn on a spinning disc sticks out past its rim.
    // A cut-out is clipped only when it is a disc: a cartridge's own shape has notches and grips.
    val disc = kind == MediaKind.DISC || kind == MediaKind.UNKNOWN
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = if (disc) Modifier.fillMaxSize().clip(outline) else Modifier.fillMaxSize(),
        )
    } else {
        Box(Modifier.fillMaxSize().clip(outline)) { DrawnMedia(kind, logo, title, caseColor, outline) }
    }
}

/** A medium's size against its case, which way it slides out, and its outline. */
internal class MediaShape(val width: Dp, val height: Dp, val slidesRight: Boolean, val outline: Shape)

internal fun mediaShape(kind: MediaKind, caseWidth: Dp, caseHeight: Dp): MediaShape {
    val landscape = caseWidth > caseHeight
    return when (kind) {
        MediaKind.DISC, MediaKind.UNKNOWN -> {
            val d = min(caseWidth, caseHeight) * 0.9f
            MediaShape(d, d, slidesRight = true, outline = CircleShape)
        }
        // The UMD: a disc in a rounded shell, about two thirds of the PSP case's width.
        MediaKind.UMD -> {
            val w = caseWidth * 0.72f
            MediaShape(w, w * 1.04f, slidesRight = true, outline = RoundedCornerShape(topStart = w * 0.5f, topEnd = w * 0.08f, bottomStart = w * 0.08f, bottomEnd = w * 0.08f))
        }
        // A DS card is 33x35 mm in a 125x137 mm case; a little larger here so it reads.
        MediaKind.CARD -> {
            val w = caseWidth * 0.4f
            MediaShape(w, w * 1.08f, slidesRight = false, outline = RoundedCornerShape(topStart = w * 0.06f, topEnd = w * 0.2f, bottomStart = w * 0.06f, bottomEnd = w * 0.06f))
        }
        MediaKind.CARTRIDGE, MediaKind.DISK_CARD -> {
            val w = if (landscape) caseWidth * 0.6f else caseWidth * 0.72f
            val h = if (landscape) w * 0.78f else w * 1.08f
            MediaShape(w, min(h, caseHeight * 0.92f), slidesRight = false, outline = RoundedCornerShape(topStart = w * 0.05f, topEnd = w * 0.05f, bottomStart = w * 0.02f, bottomEnd = w * 0.02f))
        }
    }
}

/** A generic disc, UMD, card or cartridge, drawn, with the logo (or the title) on its label. */
@Composable
private fun DrawnMedia(kind: MediaKind, logo: Any?, title: String, caseColor: Color, outline: Shape) {
    when (kind) {
        MediaKind.DISC, MediaKind.UNKNOWN -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(
                    Brush.sweepGradient(
                        listOf(Color(0xFFD9DCE3), Color(0xFFB9C7E0), Color(0xFFE8D9E6), Color(0xFFC9E0D6), Color(0xFFD9DCE3)),
                    ),
                    radius = r,
                )
                drawCircle(Color(0x33000000), radius = r, style = Stroke(1.dp.toPx()))
                drawCircle(Color(0xFFF4F4F6), radius = r * 0.66f)
                drawCircle(Color(0xFFBFC3CA), radius = r * 0.22f)
                drawCircle(Color(0xFF111114), radius = r * 0.13f)
            }
            // The label prints in the band above the hub, clear of the hole.
            MediaLabel(logo, title, Color(0xFF1C1C21), Modifier.align(BiasAlignment(0f, -0.58f)).fillMaxWidth(0.5f).fillMaxHeight(0.19f))
        }
        MediaKind.UMD -> Box(Modifier.fillMaxSize().clip(outline).background(Color(0xFF2A2B30)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize(0.82f)) {
                drawCircle(Brush.sweepGradient(listOf(Color(0xFFB8BCC6), Color(0xFFDCD3E2), Color(0xFFB8BCC6))), radius = size.minDimension / 2f)
                drawCircle(Color(0xFF2A2B30), radius = size.minDimension * 0.08f)
            }
            // Below the hub, on the lower part of the disc window.
            // One line: the band under the hub is narrow, and a second line runs over the frame.
            MediaLabel(logo, title, Color(0xFF1C1C21), Modifier.align(BiasAlignment(0f, 0.62f)).fillMaxWidth(0.56f).fillMaxHeight(0.2f), maxLines = 1)
        }
        MediaKind.CARD, MediaKind.CARTRIDGE, MediaKind.DISK_CARD -> {
            val shell = when (kind) {
                MediaKind.CARD -> Color(0xFF3A3B40)
                MediaKind.DISK_CARD -> Color(0xFFE2B33C)
                else -> lerpColor(caseColor, Color(0xFF8E8F95), 0.7f)
            }
            Box(Modifier.fillMaxSize().clip(outline).background(shell)) {
                Canvas(Modifier.fillMaxSize()) {
                    // Grip ridges along the bottom edge.
                    val ridge = size.height * 0.02f
                    for (i in 0 until 4) {
                        drawRect(Color(0x26000000), topLeft = Offset(size.width * 0.1f, size.height * 0.9f - i * ridge * 2f), size = Size(size.width * 0.8f, ridge))
                    }
                }
                // The label sits high, on the part that shows once it has slid out.
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                        .fillMaxWidth(0.84f)
                        .fillMaxHeight(0.6f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFFF2EFE8)),
                    contentAlignment = Alignment.Center,
                ) { MediaLabel(logo, title, Color(0xFF1C1C21), Modifier.fillMaxSize().padding(4.dp)) }
            }
        }
    }
}

@Composable
private fun MediaLabel(logo: Any?, title: String, ink: Color, modifier: Modifier, maxLines: Int = 4) =
    LogoOrTitle(logo, title, ink, MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier, maxLines)

/** The title logo once it has loaded; the title in text before that, or without a logo. */
@Composable
private fun LogoOrTitle(logo: Any?, title: String, ink: Color, style: androidx.compose.ui.text.TextStyle, modifier: Modifier, maxLines: Int = 4) {
    var logoShown by remember(logo) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!logoShown) {
            Text(title, style = style, color = ink, textAlign = TextAlign.Center, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
        if (logo != null) {
            AsyncImage(
                model = logo,
                // Always part of a titled case: decoration.
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { logoShown = true },
                onError = { logoShown = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)
