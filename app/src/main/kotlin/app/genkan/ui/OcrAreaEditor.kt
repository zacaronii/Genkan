package app.genkan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.ocr.OcrRegion

/**
 * Draw the area OCR reads, over a snapshot of the top screen: drag from one corner to the
 * other. Coordinates are kept as fractions of the screen, so they hold at any capture size.
 */
@Composable
fun OcrAreaEditor(
    snapshot: Bitmap?,
    current: OcrRegion,
    onSave: (OcrRegion) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var area by remember(current) { mutableStateOf(current) }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Drag over the dialogue box",
                style = MaterialTheme.typography.titleLarge,
                color = colors.primary,
                modifier = Modifier.weight(1f),
            )
            Chip("Default") { onReset() }
            Chip("Cancel") { onCancel() }
            Chip("Save") { onSave(area) }
        }
        val aspect = snapshot?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .background(Color.Black)
                .pointerInput(Unit) {
                    // From where the finger went down: a drag detector would start past the
                    // touch slop, a few dozen pixels in, and cut the box's first character off.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val from = down.position
                        drag(down.id) { change ->
                            val to = change.position
                            area = area.copy(
                                left = from.x / size.width, top = from.y / size.height,
                                right = to.x / size.width, bottom = to.y / size.height,
                            )
                            change.consume()
                        }
                        area = area.cleaned() ?: current
                    }
                },
        ) {
            if (snapshot != null) {
                Image(snapshot.asImageBitmap(), contentDescription = "The top screen", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            } else {
                Text("No picture of the top screen yet", color = colors.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
            }
            Canvas(Modifier.fillMaxSize()) {
                val l = minOf(area.left, area.right) * size.width
                val t = minOf(area.top, area.bottom) * size.height
                val r = maxOf(area.left, area.right) * size.width
                val b = maxOf(area.top, area.bottom) * size.height
                drawRect(Color(0x55E8B04A), Offset(l, t), Size(r - l, b - t))
                drawRect(Color(0xFFE8B04A), Offset(l, t), Size(r - l, b - t), style = Stroke(width = 4f))
            }
        }
        Text(
            "Genkan reads this part of the top screen whenever its text changes and then holds still.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
    }
}
