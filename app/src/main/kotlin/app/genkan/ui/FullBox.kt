package app.genkan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.genkan.library.GameEntity
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The full-box prototype's switch, for debug builds only (the FULL_BOX broadcast): while [on], the
 * shelf's focused game is drawn as a box from its scanned faces, turned [angle] degrees (0 faces
 * front; past 90 the back shows). Nothing sets it in a release build.
 */
object FullBoxPrototype {
    val on = MutableStateFlow(false)
    val angle = MutableStateFlow(28f)

    /** Whether [game] has the faces a box needs: a front and a spine. A back is shown when there is one. */
    fun canDraw(game: GameEntity): Boolean = game.box2dPath != null && game.box2dSidePath != null
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
 * that fails to load leaves the fallback. A prototype: no lighting or thickness beyond the spine.
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
    // As tall as allowed, unless the turned box would then be wider than its slot.
    val shownPerHeight = frontAspect * view.faceShown + spineAspect * view.spineShown
    val height = if (maxHeight * shownPerHeight > maxWidth) maxWidth / shownPerHeight else maxHeight
    val faceWidth = height * frontAspect
    val spineWidth = height * spineAspect
    val faceShown = faceWidth * view.faceShown
    val spineShown = spineWidth * view.spineShown
    val camera = 12f * density.density
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        if (!ready) fallback?.invoke()
        // Composed from the start, so the scans load, but unseen until both have.
        Box(Modifier.size(faceShown + spineShown, height).graphicsLayer { alpha = if (ready) 1f else 0f }) {
            // The spine meets the face along one edge, turned a right angle further.
            val spineX: Dp = if (view.spineOnLeft) 0.dp else faceShown
            Box(
                Modifier
                    .offset(x = spineX)
                    .size(spineWidth, height)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        scaleX = view.spineShown.coerceAtLeast(0.001f)
                        rotationY = if (view.spineOnLeft) -(90f - abs(view.faceTurn)) * 0.25f else (90f - abs(view.faceTurn)) * 0.25f
                        cameraDistance = camera
                    },
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
            }
            AsyncImage(
                model = face,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                onSuccess = { s ->
                    faceLoaded = true
                    s.painter.intrinsicSize.let { if (it.height > 0f) frontAspect = it.width / it.height }
                },
                modifier = Modifier
                    .offset(x = if (view.spineOnLeft) spineShown else 0.dp)
                    .size(faceWidth, height)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        scaleX = view.faceShown.coerceAtLeast(0.001f)
                        rotationY = view.faceTurn * 0.25f
                        cameraDistance = camera
                    },
            )
        }
    }
}
