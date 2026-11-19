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

/** One line OCR found, with where it sits so lines can be put in reading order. */
data class OcrLine(val text: String, val top: Int, val left: Int, val height: Int)

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
                            OcrLine(line.text, box?.top ?: 0, box?.left ?: 0, box?.height() ?: 0)
                        }
                    },
                )
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
