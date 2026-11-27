package app.genkan.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.library.CaseSpec
import app.genkan.library.GameCase
import app.genkan.library.MediaKind
import kotlin.math.sin

/**
 * A game being started with the insert animation: what to draw and for how long. [physical] and
 * [logo] are the image models the shelf uses (the server's cut-out and title logo). [skipped]
 * jumps the animation to its last frame.
 */
@Immutable
data class InsertState(
    val gameId: Long,
    val title: String,
    val kind: MediaKind,
    val spec: CaseSpec,
    val physical: Any?,
    val logo: Any?,
    val durationMs: Long,
    val skipped: Boolean = false,
)

/**
 * The beats of the insert animation, as shares of its length, with the easing between them, so
 * the composable only places things. 0 is the press of A; 1 is the end.
 */
internal object InsertBeats {
    /** The disc or cartridge rises out of its case and the machine slides up beneath it. */
    const val LIFT_END = 0.3f
    /** It goes into the machine: down into the slot, onto the spindle, into the UMD bay. */
    const val SEAT_END = 0.65f
    /** The cartridge's click: pushed a little further, then back. */
    const val CLICK_END = 0.78f
    /** The UMD door is shut by here. */
    const val DOOR_END = 0.9f
    /** Turns a disc makes while spinning up, from [SEAT_END] to the end. */
    const val SPIN_TURNS = 2f

    /** How far through the beat from [from] to [to] the animation is at [p], 0 to 1. */
    fun phase(p: Float, from: Float, to: Float): Float = ((p - from) / (to - from)).coerceIn(0f, 1f)

    fun eased(p: Float, from: Float, to: Float): Float = FastOutSlowInEasing.transform(phase(p, from, to))

    /** The disc's angle in degrees: still until seated, then speeding up, quadratically. */
    fun spin(p: Float): Float {
        val t = phase(p, SEAT_END, 1f)
        return 360f * SPIN_TURNS * t * t
    }

    /** Milliseconds per turn at the end of the spin-up, for a disc that keeps spinning after. */
    fun turnMsAtEnd(durationMs: Long): Float = ((1f - SEAT_END) * durationMs / (2f * SPIN_TURNS)).coerceAtLeast(40f)

    /** A press and release over the click beat: 0, up to 1 halfway, back to 0. */
    fun click(p: Float): Float = sin(Math.PI * phase(p, SEAT_END, CLICK_END)).toFloat()
}

/**
 * The insert animation over the whole top screen: the shelf dims, a machine for the game's media
 * slides up, and the disc or cartridge goes in. A cartridge pushes down into its slot and clicks
 * (its light comes on), a DS card does the same in a smaller slot, a disc drops onto a spindle and
 * spins up (and keeps spinning while the game is still getting ready), a UMD drops into its bay
 * and the door shuts, and a disk card slides into its drive.
 *
 * Its progress is one value read only in graphics layers, so a frame of the animation redraws
 * without recomposing. [staticProgress] fixes it, for screenshots. A tap skips it, as any button does.
 */
@Composable
fun InsertOverlay(state: InsertState, onSkip: () -> Unit, staticProgress: Float? = null) {
    val progress = remember(state.gameId) { Animatable(staticProgress ?: 0f) }
    val spinAfter = remember(state.gameId) { Animatable(0f) }
    LaunchedEffect(state.gameId, state.skipped, staticProgress) {
        when {
            staticProgress != null -> progress.snapTo(staticProgress)
            state.skipped -> progress.snapTo(1f)
            else -> progress.animateTo(1f, tween(state.durationMs.toInt(), easing = LinearEasing))
        }
        // A disc keeps spinning at full speed until the game takes over the screen.
        if (staticProgress == null && state.kind.isDisc()) {
            val turnMs = InsertBeats.turnMsAtEnd(state.durationMs).toInt()
            while (true) spinAfter.animateTo(spinAfter.value + 360f, tween(turnMs, easing = LinearEasing))
        }
    }
    val p = remember(progress) { { progress.value } }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSkip),
    ) {
        val density = LocalDensity.current
        val heightPx = with(density) { maxHeight.toPx() }
        val scene = remember(state.kind, state.spec) { sceneFor(state.kind, state.spec) }
        val machineY = heightPx * 0.55f
        val hoverY = heightPx * 0.2f
        val startY = heightPx * 0.5f

        // The shelf dims behind the machine.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.8f * InsertBeats.phase(p(), 0f, 0.2f) }.background(Color.Black))

        // Everything below is placed with its centre on the screen's centre line, at machineY.
        val machineLift = Modifier.graphicsLayer {
            val t = InsertBeats.eased(p(), 0f, InsertBeats.LIFT_END)
            translationY = machineY - size.height / 2f + (1f - t) * heightPx * 0.5f
            alpha = t
        }
        Box(Modifier.align(Alignment.TopCenter).size(scene.bodyWidth, scene.bodyHeight).then(machineLift)) {
            Canvas(Modifier.fillMaxSize()) { drawMachineBack(state.kind, scene, density.density) }
        }

        // The media: out of the case, up, then into the machine.
        val seatedY = machineY + with(density) { scene.seatOffset.toPx() }
        val clickPx = with(density) { 6.dp.toPx() }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .size(scene.media.width, scene.media.height)
                .graphicsLayer {
                    val now = p()
                    val lift = InsertBeats.eased(now, 0f, InsertBeats.LIFT_END)
                    val seat = InsertBeats.eased(now, InsertBeats.LIFT_END, InsertBeats.SEAT_END)
                    val y = startY + (hoverY - startY) * lift + (seatedY - hoverY) * seat
                    translationY = y - size.height / 2f + clickPx * InsertBeats.click(now) * if (state.kind.isDisc()) 0f else 1f
                    val s = 0.8f + 0.2f * lift + (if (state.kind.isDisc() || state.kind == MediaKind.UMD) 0.1f * (1f - seat) * lift else 0f)
                    scaleX = s
                    scaleY = s
                    alpha = InsertBeats.phase(now, 0f, 0.08f)
                    if (state.kind.isDisc()) rotationZ = InsertBeats.spin(now) + spinAfter.value
                },
        ) {
            MediaArt(state.kind, state.physical, state.logo, state.title, Color(state.spec.color), scene.media.outline)
        }

        // The machine's front: the slot's lip over a cartridge, the spindle over a disc, the door over a UMD.
        Box(Modifier.align(Alignment.TopCenter).size(scene.bodyWidth, scene.bodyHeight).then(machineLift)) {
            Canvas(Modifier.fillMaxSize()) { drawMachineFront(state.kind, scene, p(), density.density) }
        }

        // A dark ground under the label, so the shelf's caption row doesn't show through it.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(110.dp)
                .graphicsLayer { alpha = InsertBeats.phase(p(), 0.05f, 0.25f) }
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black, Color.Black))),
        )
        Text(
            "Starting ${state.title}",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFFEDEDED),
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
                .graphicsLayer { alpha = InsertBeats.phase(p(), 0.1f, 0.3f) },
        )
    }
}

private fun MediaKind.isDisc() = this == MediaKind.DISC || this == MediaKind.UNKNOWN

/**
 * The machine for one kind of media and where the media sits in it. [seatOffset] is the seated
 * media's centre below the machine's centre.
 */
private class Scene(
    val media: MediaShape,
    val bodyWidth: Dp,
    val bodyHeight: Dp,
    val seatOffset: Dp,
)

private fun sceneFor(kind: MediaKind, spec: CaseSpec): Scene {
    // The media at the size the shelf would draw it against a case 300 dp across, scaled up for
    // the small ones so each reads at the middle of the screen.
    val (caseW, caseH) = GameCase.fit(spec.aspect, 300f, 300f)
    val base = mediaShape(kind, caseW.dp, caseH.dp)
    val grow = when (kind) {
        MediaKind.DISC, MediaKind.UNKNOWN -> 200f / base.width.value
        MediaKind.UMD -> 1.3f
        MediaKind.CARD -> 1.3f
        MediaKind.CARTRIDGE, MediaKind.DISK_CARD -> (170f / base.width.value).coerceAtMost(1.3f)
    }
    val media = MediaShape(base.width * grow, base.height * grow, base.slidesRight, base.outline)
    return when (kind) {
        MediaKind.DISC, MediaKind.UNKNOWN -> Scene(media, media.width * 1.25f, media.height * 1.25f, 0.dp)
        MediaKind.UMD -> Scene(media, media.width * 1.9f, media.height * 1.3f, 0.dp)
        // Slots: the machine's top edge is the slot; the seated media shows a little above it.
        MediaKind.CARTRIDGE -> slotScene(media, widthFactor = 2.1f, showing = 0.45f)
        MediaKind.CARD -> slotScene(media, widthFactor = 2.6f, showing = 0.18f)
        MediaKind.DISK_CARD -> slotScene(media, widthFactor = 2.1f, showing = 0.1f)
    }
}

private fun slotScene(media: MediaShape, widthFactor: Float, showing: Float): Scene {
    val bodyHeight = media.height * 1.1f
    // The slot is the body's top edge; the media's centre is (0.5 - showing) of its height below it.
    val seat = -bodyHeight / 2 + media.height * (0.5f - showing)
    return Scene(media, media.width * widthFactor, bodyHeight, seat)
}

/** What sits behind the media: the body of a disc drive or UMD bay, the dark slot opening. */
private fun DrawScope.drawMachineBack(kind: MediaKind, scene: Scene, density: Float) {
    val body = Color(0xFF2B2D33)
    val edge = Color(0x33FFFFFF)
    when (kind) {
        MediaKind.DISC, MediaKind.UNKNOWN -> {
            drawRoundRect(body, cornerRadius = CornerRadius(18f * density))
            drawRoundRect(edge, cornerRadius = CornerRadius(18f * density), style = Stroke(1.5f * density))
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = scene.media.width.value * density / 2f
            drawCircle(Color(0xFF3A3D45), radius = r * 1.02f, center = c)
            drawCircle(Color(0xFF23252A), radius = r * 0.5f, center = c)
            drawCircle(edge, radius = r * 1.02f, center = c, style = Stroke(1f * density))
        }
        MediaKind.UMD -> {
            drawRoundRect(body, cornerRadius = CornerRadius(40f * density))
            drawRoundRect(edge, cornerRadius = CornerRadius(40f * density), style = Stroke(1.5f * density))
            val w = scene.media.width.value * density * 1.06f
            val h = scene.media.height.value * density * 1.06f
            drawRoundRect(
                Color(0xFF17181C),
                topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(10f * density),
            )
        }
        MediaKind.CARTRIDGE, MediaKind.CARD, MediaKind.DISK_CARD -> {
            // Only the slot's dark mouth shows behind the media; the body is drawn in front.
            val w = scene.media.width.value * density * 1.08f
            drawRoundRect(
                Color(0xFF0E0F12),
                topLeft = Offset((size.width - w) / 2f, -6f * density),
                size = Size(w, 14f * density),
                cornerRadius = CornerRadius(3f * density),
            )
        }
    }
}

/** What sits in front of the media, at progress [p]. */
private fun DrawScope.drawMachineFront(kind: MediaKind, scene: Scene, p: Float, density: Float) {
    val edge = Color(0x40FFFFFF)
    when (kind) {
        MediaKind.DISC, MediaKind.UNKNOWN -> {
            // The spindle, over the disc's hole once it has landed.
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = scene.media.width.value * density / 2f
            drawCircle(Color(0xFFC9CCD3), radius = r * 0.085f, center = c, alpha = InsertBeats.phase(p, 0.55f, InsertBeats.SEAT_END))
            drawCircle(Color(0xFF6E727B), radius = r * 0.035f, center = c)
            drawLed(Offset(size.width - 22f * density, size.height - 18f * density), InsertBeats.phase(p, InsertBeats.SEAT_END, 0.75f), density)
        }
        MediaKind.UMD -> {
            // The door, hinged at the bay's top, swings down over the UMD.
            val w = scene.media.width.value * density * 1.12f
            val h = scene.media.height.value * density * 1.12f
            val shut = InsertBeats.eased(p, InsertBeats.SEAT_END, InsertBeats.DOOR_END)
            val top = (size.height - h) / 2f
            val visible = h * (0.06f + 0.94f * shut)
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0x993A3D45), Color(0xB31C1D22)), startY = top, endY = top + visible),
                topLeft = Offset((size.width - w) / 2f, top),
                size = Size(w, visible),
                cornerRadius = CornerRadius(10f * density),
            )
            drawRoundRect(edge, topLeft = Offset((size.width - w) / 2f, top), size = Size(w, visible), cornerRadius = CornerRadius(10f * density), style = Stroke(1.5f * density))
            drawLed(Offset(size.width - 34f * density, size.height / 2f), InsertBeats.phase(p, InsertBeats.DOOR_END, 0.95f), density)
        }
        MediaKind.CARTRIDGE, MediaKind.CARD, MediaKind.DISK_CARD -> {
            // The body, covering the part of the media that has gone into the slot.
            val body = when (kind) {
                MediaKind.CARD -> Color(0xFF2A2B31)
                MediaKind.DISK_CARD -> Color(0xFF7A1F25)
                else -> Color(0xFF8D8E94)
            }
            drawRoundRect(
                Brush.verticalGradient(listOf(body, lerp(body, Color.Black, 0.35f))),
                cornerRadius = CornerRadius(14f * density),
            )
            drawRoundRect(edge, cornerRadius = CornerRadius(14f * density), style = Stroke(1.5f * density))
            // The slot's lip along the top edge.
            val w = scene.media.width.value * density * 1.12f
            drawRoundRect(
                Color(0xFF16171B),
                topLeft = Offset((size.width - w) / 2f, 0f),
                size = Size(w, 5f * density),
                cornerRadius = CornerRadius(2f * density),
            )
            // Grip ridges down the front.
            for (i in 0 until 5) {
                val y = size.height * (0.45f + i * 0.08f)
                drawRect(Color(0x26000000), topLeft = Offset(size.width * 0.12f, y), size = Size(size.width * 0.18f, 3f * density))
                drawRect(Color(0x26000000), topLeft = Offset(size.width * 0.7f, y), size = Size(size.width * 0.18f, 3f * density))
            }
            // The power light comes on with the click.
            drawLed(Offset(size.width / 2f, size.height * 0.72f), InsertBeats.phase(p, (InsertBeats.SEAT_END + InsertBeats.CLICK_END) / 2f, InsertBeats.CLICK_END), density)
        }
    }
}

/** A small power light, off (dark) at 0 and lit red with a glow at 1. */
private fun DrawScope.drawLed(at: Offset, on: Float, density: Float) {
    val r = 4.5f * density
    if (on > 0f) drawCircle(Brush.radialGradient(listOf(Color(0x99FF3B30), Color.Transparent), center = at, radius = r * 4f), radius = r * 4f, center = at, alpha = on)
    drawCircle(lerp(Color(0xFF3A1714), Color(0xFFFF4A3D), on), radius = r, center = at)
}

private fun lerp(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/**
 * A disk swap shown on the bottom screen: [from] is the disk or side that comes out, [to] the one
 * that goes in, drawn with the game's own media as the insert animation draws it ([physical], [logo]).
 * [key] tells one swap from the next.
 */
@Immutable
data class DiskSwapState(
    val key: Long,
    val title: String,
    val kind: MediaKind,
    val spec: CaseSpec,
    val physical: Any?,
    val logo: Any?,
    val from: String,
    val to: String,
    val durationMs: Long = SwapBeats.DURATION_MS,
)

/**
 * The beats of a swap, as shares of its length: the insert animation run backwards takes the
 * media out of the machine and it slides away; the next slides in and the insert animation runs
 * forwards from where the media hovers. The drive is empty from the start until [IN_SEAT] (the core
 * is told the next disk then: [emptyMs]).
 */
internal object SwapBeats {
    const val DURATION_MS = 2_000L
    /** Out of the machine and hovering. */
    const val OUT_LIFTED = 0.25f
    /** Gone off to the side. */
    const val OUT_GONE = 0.42f
    /** The next one in over the machine. */
    const val IN_OVER = 0.58f
    /** It starts going in: the core gets it. */
    const val IN_SEAT = 0.6f
    /** Seated (and clicked, or spinning). */
    const val IN_DONE = 0.85f

    /** How long the drive stays empty, for a swap of [durationMs]. */
    fun emptyMs(durationMs: Long): Int = (IN_SEAT * durationMs).toInt()

    /** Where the insert animation's media and machine are at swap progress [p], 0 to 1. */
    fun insertProgress(p: Float): Float = when {
        p < OUT_LIFTED -> 1f - (1f - InsertBeats.LIFT_END) * InsertBeats.phase(p, 0f, OUT_LIFTED)
        p < IN_SEAT -> InsertBeats.LIFT_END
        else -> InsertBeats.LIFT_END + (1f - InsertBeats.LIFT_END) * InsertBeats.phase(p, IN_SEAT, IN_DONE)
    }

    /** Sideways, as a share of the screen's width: out to the left, in from the right. */
    fun sideways(p: Float): Float = when {
        p < OUT_LIFTED -> 0f
        p < OUT_GONE -> -InsertBeats.eased(p, OUT_LIFTED, OUT_GONE)
        p < IN_OVER -> 1f - InsertBeats.eased(p, OUT_GONE, IN_OVER)
        else -> 0f
    }

    /** Whether the media drawn at [p] is the one going in. */
    fun incoming(p: Float): Boolean = p >= OUT_GONE
}

/**
 * The swap over the bottom screen: a machine for the game's media, the disk or disc in it lifted
 * out and slid away, the next slid in and put in (a cartridge-like disk card clicks, a disc spins
 * up), then which one is in now. Its progress is read only in graphics layers. [staticProgress]
 * fixes it, for screenshots.
 */
@Composable
fun DiskSwapOverlay(state: DiskSwapState, staticProgress: Float? = null) {
    val progress = remember(state.key) { Animatable(staticProgress ?: 0f) }
    LaunchedEffect(state.key, staticProgress) {
        if (staticProgress != null) progress.snapTo(staticProgress)
        else progress.animateTo(1f, tween(state.durationMs.toInt(), easing = LinearEasing))
    }
    val p = remember(progress) { { progress.value } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val heightPx = with(density) { maxHeight.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }
        val scene = remember(state.kind, state.spec) { sceneFor(state.kind, state.spec) }
        val machineY = heightPx * 0.58f
        val hoverY = heightPx * 0.24f
        val seatedY = machineY + with(density) { scene.seatOffset.toPx() }
        val clickPx = with(density) { 6.dp.toPx() }

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.88f * InsertBeats.phase(p(), 0f, 0.08f) }.background(Color.Black))
        val machine = Modifier.graphicsLayer {
            translationY = machineY - size.height / 2f
            alpha = InsertBeats.phase(p(), 0f, 0.08f)
        }
        Box(Modifier.align(Alignment.TopCenter).size(scene.bodyWidth, scene.bodyHeight).then(machine)) {
            Canvas(Modifier.fillMaxSize()) { drawMachineBack(state.kind, scene, density.density) }
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .size(scene.media.width, scene.media.height)
                .graphicsLayer {
                    val now = p()
                    val inserted = SwapBeats.insertProgress(now)
                    val seat = InsertBeats.eased(inserted, InsertBeats.LIFT_END, InsertBeats.SEAT_END)
                    val y = hoverY + (seatedY - hoverY) * seat
                    translationY = y - size.height / 2f + clickPx * InsertBeats.click(inserted) * if (state.kind.isDisc()) 0f else 1f
                    translationX = SwapBeats.sideways(now) * widthPx
                    alpha = InsertBeats.phase(now, 0f, 0.08f)
                    if (state.kind.isDisc()) rotationZ = InsertBeats.spin(inserted)
                },
        ) {
            MediaArt(state.kind, state.physical, state.logo, state.title, Color(state.spec.color), scene.media.outline)
        }
        Box(Modifier.align(Alignment.TopCenter).size(scene.bodyWidth, scene.bodyHeight).then(machine)) {
            Canvas(Modifier.fillMaxSize()) { drawMachineFront(state.kind, scene, SwapBeats.insertProgress(p()), density.density) }
        }

        // Which one is coming out, then which is in.
        Text(
            state.from,
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFBDBDBD),
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
                .graphicsLayer { alpha = InsertBeats.phase(p(), 0.02f, 0.1f) * (1f - InsertBeats.phase(p(), SwapBeats.OUT_LIFTED, SwapBeats.OUT_GONE)) },
        )
        Text(
            "${state.to} in",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFFEDEDED),
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
                .graphicsLayer { alpha = InsertBeats.phase(p(), SwapBeats.IN_DONE, 0.95f) },
        )
    }
}
