package app.genkan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.genkan.library.GameEntity
import app.genkan.library.hasBoxFaces
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The shelf's full box: while [on] (the "Full box" setting, mirrored here by HomeActivity), the
 * focused game is drawn as a box from its scanned faces, turned [angle] degrees (0 faces front;
 * past 90 the back shows). The angle is 28 unless a debug build's FULL_BOX broadcast sets it.
 */
object FullBoxShelf {
    val on = MutableStateFlow(true)
    val angle = MutableStateFlow(28f)

    /** Whether [game] has the faces a box needs: a front and a spine. A back is shown when there is one. */
    fun canDraw(game: GameEntity): Boolean = game.hasBoxFaces()
}

/** Where a box's two visible faces go, for a turn of [angle] degrees: plain numbers, for the tests. */
internal data class BoxView(
    /** The face toward the viewer: the back once the box has turned past 90 degrees. */
    val back: Boolean,
    /** The facing face's turn from flat, degrees; the spine is a right angle from it. */
    val faceTurn: Float,
    /**
     * The side face on the facing face's left: whichever side is in view is on the left when the box
     * turns positive, front or back toward the viewer. It's the spine itself when the front is turned
     * positive or the back negative; otherwise the spine's scan stands in for the side without one.
     */
    val spineOnLeft: Boolean,
    /** How wide the facing face and the spine look, as shares of their flat widths. */
    val faceShown: Float,
    val spineShown: Float,
) {
    companion object {
        fun of(angle: Float): BoxView {
            val a = ((angle % 360f) + 360f) % 360f
            val back = a > 90f && a < 270f
            val turn = if (back) a - 180f else if (a > 180f) a - 360f else a
            val rad = Math.toRadians(turn.toDouble())
            return BoxView(
                back = back,
                faceTurn = turn,
                spineOnLeft = turn >= 0f,
                faceShown = abs(cos(rad)).toFloat(),
                spineShown = abs(sin(rad)).toFloat(),
            )
        }
    }
}

/**
 * A game box drawn from its scans: [front] (or [back], seen past 90 degrees) and [spine] at a
 * right angle, as tall as [maxHeight] and no wider than [maxWidth], turned [angle] degrees about
 * its vertical axis. The faces keep the scans' own proportions once they load; a spine scanned
 * lying down (wider than tall, as some consoles' are) is stood up. With a [fallback] (the game's
 * case), that is drawn instead until both faces have loaded, so the slot is never empty; a face
 * that fails to load leaves the fallback. [behind] is drawn behind the box, given its width and
 * height (the disc or cartridge peeking out, as from the case). [faceOverlay] is drawn on the facing
 * face and [spineOverlay] on the spine, each in that face's own turned layer, so they stay on it at
 * any angle (the download badge and dimming), once the box shows. No lighting or thickness beyond the spine.
 */
@Composable
fun FullBox(
    front: Any?,
    spine: Any?,
    back: Any?,
    maxWidth: Dp,
    maxHeight: Dp,
    angle: Float,
    modifier: Modifier = Modifier,
    fallback: (@Composable () -> Unit)? = null,
    behind: (@Composable BoxScope.(width: Dp, height: Dp) -> Unit)? = null,
    faceOverlay: (@Composable BoxScope.() -> Unit)? = null,
    spineOverlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val density = LocalDensity.current
    var frontAspect by remember(front) { mutableFloatStateOf(0.72f) }
    var spineAspect by remember(spine) { mutableFloatStateOf(0.1f) }
    var spineLying by remember(spine) { mutableStateOf(false) }
    val view = BoxView.of(angle)
    val face = if (view.back) back ?: front else front
    var faceLoaded by remember(face) { mutableStateOf(false) }
    var spineLoaded by remember(spine) { mutableStateOf(false) }
    val ready = fallback == null || (faceLoaded && spineLoaded)
    // Sized once for every angle, so a turning box keeps its size: as tall as allowed, unless its
    // widest view would then be wider than the slot.
    val height = boxHeight(maxWidth, maxHeight, frontAspect, spineAspect)
    val faceWidth = height * frontAspect
    val spineWidth = height * spineAspect
    val faceShown = faceWidth * view.faceShown
    val spineShown = spineWidth * view.spineShown
    // Each face is tilted a little further in its own layer, for depth; the spine meets the face's edge.
    val faceTilt = view.faceTurn * TILT
    val spineTilt = if (view.spineOnLeft) -(90f - abs(view.faceTurn)) * TILT else (90f - abs(view.faceTurn)) * TILT
    val spineX: Dp = if (view.spineOnLeft) 0.dp else faceShown
    val faceX: Dp = if (view.spineOnLeft) spineShown else 0.dp
    // Where the drawn faces end, the tilts foreshortening them past their layout, for the peek;
    // worked out here rather than measured, so it is right in the same frame as the faces.
    val drawn = maxOf(faceX + faceShown * cosDegrees(faceTilt), spineX + spineShown * cosDegrees(spineTilt))
    val camera = 12f * density.density
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        if (!ready) fallback?.invoke()
        // Composed from the start, so the scans load, but unseen until both have.
        Box(Modifier.size(faceShown + spineShown, height).graphicsLayer { alpha = if (ready) 1f else 0f }) {
            behind?.let { media -> Box(Modifier.size(drawn, height)) { media(this, drawn, height) } }
            // The spine meets the face along one edge, turned a right angle further.
            Box(
                Modifier
                    .offset(x = spineX)
                    // Each face keeps its full width, though its layer then narrows it below the box's.
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .size(spineWidth, height)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        scaleX = view.spineShown.coerceAtLeast(0.001f)
                        rotationY = spineTilt
                        cameraDistance = camera
                    }
                    .testTag(SPINE_TAG),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = spine,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    onSuccess = { s ->
                        spineLoaded = true
                        s.painter.intrinsicSize.let {
                            if (it.height > 0f && it.width > 0f) {
                                spineLying = it.width > it.height
                                spineAspect = minOf(it.width, it.height) / maxOf(it.width, it.height)
                            }
                        }
                    },
                    // A spine scanned lying down is drawn lying, then turned upright.
                    modifier = if (spineLying) Modifier.requiredSize(height, spineWidth).rotate(90f) else Modifier.size(spineWidth, height),
                )
                spineOverlay?.invoke(this)
            }
            Box(
                Modifier
                    .offset(x = faceX)
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .size(faceWidth, height)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        scaleX = view.faceShown.coerceAtLeast(0.001f)
                        rotationY = faceTilt
                        cameraDistance = camera
                    }
                    .testTag(FACE_TAG),
            ) {
                AsyncImage(
                    model = face,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    onSuccess = { s ->
                        faceLoaded = true
                        s.painter.intrinsicSize.let { if (it.height > 0f) frontAspect = it.width / it.height }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                faceOverlay?.invoke(this)
            }
        }
    }
}

/** How much further each face is tilted in its own layer, as a share of the box's turn. */
private const val TILT = 0.25f

private fun cosDegrees(degrees: Float): Float = cos(Math.toRadians(degrees.toDouble())).toFloat()

/** Test tags of the box's two drawn faces, for checking what's drawn on them. */
internal const val FACE_TAG = "full-box-face"
internal const val SPINE_TAG = "full-box-spine"

/**
 * A box's height in a [maxWidth] by [maxHeight] slot: as tall as allowed, unless its widest view
 * over a whole turn (front and spine of [frontAspect] and [spineAspect], widths per height, at
 * once: their hypotenuse) would then be wider than the slot. One size at every angle.
 */
internal fun boxHeight(maxWidth: Dp, maxHeight: Dp, frontAspect: Float, spineAspect: Float): Dp {
    val widest = kotlin.math.sqrt(frontAspect * frontAspect + spineAspect * spineAspect)
    return if (maxHeight * widest > maxWidth) maxWidth / widest else maxHeight
}
