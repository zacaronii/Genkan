package app.genkan.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A small ring saying something is under way: filled to [progress] (0 to 1) when it's known, as
 * the download badge's ring is, else a quarter of it going round.
 */
@Composable
fun BusyRing(progress: Float?, color: Color, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    val turn by rememberInfiniteTransition(label = "busy").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "turn",
    )
    Canvas(modifier.size(size)) {
        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, useCenter = false, style = stroke)
        if (progress != null) drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), useCenter = false, style = stroke)
        else drawArc(color, turn - 90f, 90f, useCenter = false, style = stroke)
    }
}
