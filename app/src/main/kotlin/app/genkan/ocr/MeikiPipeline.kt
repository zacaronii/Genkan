// Pre- and post-processing adapted from PlayTranslate (GPL-3.0) @ 343c74f:
// app/src/main/java/com/playtranslate/ocr/meiki/MeikiSession.kt, and from the models' own
// inference scripts (rtr46/meiki.text.detect.v0, rtr46/meiki.txt.recognition.v0).
package app.genkan.ocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The arithmetic around Meiki's three models, kept free of Android so it is testable. Meiki was
 * trained on cv2 images, so every input is BGR scaled to 0..1, planar (NCHW), with no mean or
 * std. Both models are D-FINE detectors: the detector finds text lines, and the recognisers find
 * characters, each as a Unicode code point with a box and a score.
 */
object MeikiPipeline {

    const val DET_W = 960
    const val DET_H = 544
    const val REC_H_W = 960
    const val REC_H_H = 32
    const val REC_V_W = 32
    const val REC_V_H = 480

    private const val DET_CONF = 0.4f
    /** The detector proposes a stack of near-copies per line; same-line pairs sit at IoU ≥ 0.96. */
    private const val DET_DEDUP_IOU = 0.8f
    private const val REC_CONF = 0.1f
    private const val OVERLAP = 0.3f
    /** Boxes taller than this many widths are vertical text. */
    private const val VERTICAL_ASPECT = 1.3f
    /** Padding across a line, as a share of its thickness. */
    private const val CROSS_PAD = 0.1f
    /**
     * Padding before a line's first character. A 「 inks only the far corner of its cell, so the
     * detector's box often starts after it; much more than this and the recogniser invents marks
     * (、桜の… at 0.75 on the Thor's test card).
     */
    private const val LEAD_PAD = 0.3f

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val vertical get() = height > width * VERTICAL_ASPECT
    }

    data class Line(val box: Box, val score: Float)

    /** A letterboxed detector input: the image scaled by [scale] into the top-left corner. */
    class DetInput(val tensor: FloatArray, val scale: Float)

    /** Size the image is scaled to before [detInput]: aspect kept, fitting 960x544. */
    fun detScale(width: Int, height: Int): Float = min(DET_W.toFloat() / width, DET_H.toFloat() / height)

    /**
     * Upstream meikiocr letterboxes rather than stretching: a stretched dialogue-box crop shifts
     * the detector's scores enough to drop or double lines. [pixels] are ARGB, already scaled.
     */
    fun detInput(pixels: IntArray, width: Int, height: Int, scale: Float): DetInput =
        DetInput(planarBgr(pixels, width, height, DET_W, DET_H), scale)

    /** Confident line boxes in the original image, near-copies removed. */
    fun lines(boxes: FloatArray, scores: FloatArray, scale: Float, width: Int, height: Int): List<Line> {
        val inv = 1f / scale
        val found = scores.indices.filter { scores[it] >= DET_CONF }.mapNotNull { i ->
            val x1 = (boxes[i * 4] * inv).roundToInt().coerceIn(0, width - 1)
            val y1 = (boxes[i * 4 + 1] * inv).roundToInt().coerceIn(0, height - 1)
            val x2 = (boxes[i * 4 + 2] * inv).roundToInt().coerceIn(1, width)
            val y2 = (boxes[i * 4 + 3] * inv).roundToInt().coerceIn(1, height)
            if (x2 - x1 >= 2 && y2 - y1 >= 2) Line(Box(x1, y1, x2, y2), scores[i]) else null
        }
        val kept = ArrayList<Line>()
        for (line in found.sortedByDescending { it.score }) {
            if (kept.none { iou(it.box, line.box) > DET_DEDUP_IOU }) kept += line
        }
        return kept
    }

    /** The crop to recognise for [box]: padded across the line and before it, inside the image. */
    fun cropFor(box: Box, width: Int, height: Int): Box {
        if (box.vertical) {
            val pad = (box.width * CROSS_PAD).roundToInt()
            val lead = (box.width * LEAD_PAD).roundToInt()
            return Box(max(0, box.left - pad), max(0, box.top - lead), min(width, box.right + pad), box.bottom)
        }
        val pad = (box.height * CROSS_PAD).roundToInt()
        val lead = (box.height * LEAD_PAD).roundToInt()
        return Box(max(0, box.left - lead), max(0, box.top - pad), box.right, min(height, box.bottom + pad))
    }

    /** Where a crop goes on the recogniser's canvas: aspect kept, anchored top-left. */
    fun recSize(cropW: Int, cropH: Int, vertical: Boolean): Pair<Int, Int> {
        if (vertical) {
            var w = REC_V_W
            var h = max(1, (cropH * (REC_V_W.toDouble() / cropW)).roundToInt())
            if (h > REC_V_H) { h = REC_V_H; w = max(1, (cropW * (REC_V_H.toDouble() / cropH)).roundToInt()) }
            return w to h
        }
        var h = REC_H_H
        var w = max(1, (cropW * (REC_H_H.toDouble() / cropH)).roundToInt())
        if (w > REC_H_W) { w = REC_H_W; h = max(1, (cropH * (REC_H_W.toDouble() / cropW)).roundToInt()) }
        return w to h
    }

    /** One recogniser canvas from a crop already scaled to [recSize]; the rest stays black. */
    fun recInput(pixels: IntArray, width: Int, height: Int, vertical: Boolean): FloatArray =
        if (vertical) planarBgr(pixels, width, height, REC_V_W, REC_V_H)
        else planarBgr(pixels, width, height, REC_H_W, REC_H_H)

    /**
     * The characters of one canvas in reading order. A character whose extent along the line
     * overlaps a more confident one by more than 30% is the same character found twice.
     */
    fun text(codes: IntArray, boxes: FloatArray, scores: FloatArray, vertical: Boolean): String {
        val axis = if (vertical) 1 else 0
        class Hit(val text: String, val score: Float, val lo: Float, val hi: Float)
        val hits = scores.indices.mapNotNull { i ->
            if (scores[i] < REC_CONF || codes[i] <= 0 || !Character.isValidCodePoint(codes[i])) return@mapNotNull null
            Hit(String(Character.toChars(codes[i])), scores[i], boxes[i * 4 + axis], boxes[i * 4 + axis + 2])
        }.sortedByDescending { it.score }
        val kept = ArrayList<Hit>()
        for (hit in hits) {
            val extent = hit.hi - hit.lo + 1e-6f
            if (kept.none { max(0f, min(hit.hi, it.hi) - max(hit.lo, it.lo)) / extent > OVERLAP }) kept += hit
        }
        return kept.sortedBy { it.lo }.joinToString("") { it.text }
    }

    private fun iou(a: Box, b: Box): Float {
        val iw = min(a.right, b.right) - max(a.left, b.left)
        val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw.toFloat() * ih
        return inter / (a.width.toFloat() * a.height + b.width.toFloat() * b.height - inter)
    }

    /** ARGB pixels into the top-left of a [canvasW]x[canvasH] planar BGR tensor, 0..1. */
    private fun planarBgr(pixels: IntArray, width: Int, height: Int, canvasW: Int, canvasH: Int): FloatArray {
        val out = FloatArray(3 * canvasW * canvasH)
        val plane = canvasW * canvasH
        for (y in 0 until min(height, canvasH)) {
            for (x in 0 until min(width, canvasW)) {
                val p = pixels[y * width + x]
                val d = y * canvasW + x
                out[d] = (p and 0xff) / 255f
                out[plane + d] = ((p ushr 8) and 0xff) / 255f
                out[2 * plane + d] = ((p ushr 16) and 0xff) / 255f
            }
        }
        return out
    }
}
