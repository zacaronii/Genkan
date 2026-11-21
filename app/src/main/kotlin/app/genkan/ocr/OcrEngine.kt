package app.genkan.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One line OCR found, with where it sits so lines can be put in reading order (and drawn over the
 * game). [ends]: where each character the engine boxed ends, along the line, when it gives them.
 * [vertical]: the line runs top to bottom, as the engine read it; decided once, there, so its
 * [ends] and a tap on it are measured along the same axis.
 */
data class OcrLine(
    val text: String,
    val top: Int,
    val left: Int,
    val height: Int,
    val width: Int = 0,
    val ends: List<CharEnd> = emptyList(),
    val vertical: Boolean = false,
)

/** The character at [offset] in its line ends at [end] (pixels: x across a line, y down a vertical one). */
data class CharEnd(val offset: Int, val end: Int)

interface OcrEngine {
    val name: String
    suspend fun read(bitmap: Bitmap): List<OcrLine>
}

/** Puts OCR's lines in reading order (top to bottom, then left to right) as one line of text. */
object OcrText {
    fun join(lines: List<OcrLine>): String {
        if (lines.isEmpty()) return ""
        val rowHeight = lines.map { it.height }.sorted()[lines.size / 2].coerceAtLeast(1)
        // Lines whose tops are within half a line of each other are one row.
        return lines.sortedWith(compareBy({ it.top / (rowHeight / 2 + 1) }, { it.left }))
            .joinToString("") { it.text.trim() }
    }
}

/** Boxes taller than this many widths hold vertical text. */
const val VERTICAL_ASPECT = 1.5f

/** Google's on-device Japanese recogniser, bundled with the app: no download, no network. */
@Singleton
class MlKitJapaneseOcr @Inject constructor() : OcrEngine {
    override val name = "ML Kit Japanese"

    private val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    override suspend fun read(bitmap: Bitmap): List<OcrLine> = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                cont.resume(
                    text.textBlocks.flatMap { block ->
                        block.lines.map { line ->
                            val box = line.boundingBox
                            val width = box?.width() ?: 0
                            val height = box?.height() ?: 0
                            val vertical = height > width * VERTICAL_ASPECT
                            // Each symbol's place in the line's text, found in order, with its far edge.
                            var at = 0
                            val ends = line.elements.flatMap { it.symbols }.mapNotNull { symbol ->
                                val edge = symbol.boundingBox ?: return@mapNotNull null
                                val offset = line.text.indexOf(symbol.text, at).takeIf { it >= 0 } ?: return@mapNotNull null
                                at = offset + symbol.text.length
                                CharEnd(offset, if (vertical) edge.bottom else edge.right)
                            }
                            OcrLine(line.text, box?.top ?: 0, box?.left ?: 0, height, width, ends, vertical)
                        }
                    },
                )
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
