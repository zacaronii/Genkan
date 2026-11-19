package app.genkan.ocr

import kotlin.math.abs

/**
 * Decides when to read the dialogue box. Games write text a character at a time, so reading
 * on every change gives half-lines; the latch waits until the box has changed and then held
 * still for [settleMs] (Furoshiki's console OCR and PlayTranslate's frame latch work the same
 * way). Frames are compared by a coarse grey signature, so noise doesn't count as a change.
 */
class FrameLatch(private val settleMs: Long = 450, private val threshold: Int = 12) {

    private var last: IntArray? = null
    private var changedAt = 0L
    private var pending = false

    /** A frame's signature arrived at [now]; true when the box should be read now. */
    fun onFrame(signature: IntArray, now: Long): Boolean {
        val previous = last
        last = signature
        if (previous == null || differs(previous, signature)) {
            changedAt = now
            pending = true
            return false
        }
        if (pending && now - changedAt >= settleMs) {
            pending = false
            return true
        }
        return false
    }

    /** Forget the box: the next frame counts as a change (a new game, a new area). */
    fun reset() {
        last = null
        pending = false
    }

    /**
     * Any one cell moving by [threshold] grey levels is a change: a newly written character
     * touches one or two cells of the grid, so an average over the box would miss it.
     */
    private fun differs(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return true
        for (i in a.indices) if (abs(a[i] - b[i]) >= threshold) return true
        return false
    }

    companion object {
        const val COLUMNS = 32
        const val ROWS = 12

        /** Mean grey of each cell of a COLUMNS x ROWS grid over [pixels] (ARGB, row-major). */
        fun signature(pixels: IntArray, width: Int, height: Int): IntArray {
            val sums = LongArray(COLUMNS * ROWS)
            val counts = IntArray(COLUMNS * ROWS)
            // Every second pixel each way is plenty for a change test.
            for (y in 0 until height step 2) {
                val row = (y * ROWS / height).coerceAtMost(ROWS - 1)
                for (x in 0 until width step 2) {
                    val p = pixels[y * width + x]
                    val grey = ((p shr 16 and 0xff) * 3 + (p shr 8 and 0xff) * 6 + (p and 0xff)) / 10
                    val cell = row * COLUMNS + (x * COLUMNS / width).coerceAtMost(COLUMNS - 1)
                    sums[cell] += grey.toLong()
                    counts[cell]++
                }
            }
            return IntArray(sums.size) { if (counts[it] == 0) 0 else (sums[it] / counts[it]).toInt() }
        }
    }
}
