package app.genkan.core

import android.util.Log
import app.genkan.anki.GifEncoder
import java.nio.ByteBuffer

/**
 * The last few seconds of the game's picture, small, for a card's animated Picture: a shot every
 * [intervalMs] (taken on the emulation thread, scaled down by the GPU), the newest [capacity]
 * kept. How long the shots take is logged now and then, the cost of keeping it.
 */
class FrameRing(private val capacity: Int = CAPACITY, private val intervalMs: Long = INTERVAL_MS) {

    /** A shot: RGBA bytes, bottom row first, as GL reads them. */
    private class Shot(val rgba: ByteArray, val width: Int, val height: Int, val atMs: Long)

    private val shots = ArrayDeque<Shot>()
    private var lastMs = 0L
    private var spentNs = 0L
    private var taken = 0
    private var reportedMs = 0L

    /** Whether a shot is due at [nowMs]. */
    @Synchronized
    fun due(nowMs: Long): Boolean = nowMs - lastMs >= intervalMs

    /** Keeps [frame] (as [GLRetroView]'s scaled read gives it), taken at [nowMs] in [spentNs]. */
    @Synchronized
    fun add(frame: Triple<ByteBuffer, Int, Int>, nowMs: Long, spentNs: Long) {
        val (pixels, width, height) = frame
        val bytes = ByteArray(width * height * 4)
        pixels.rewind()
        pixels.get(bytes)
        shots.addLast(Shot(bytes, width, height, nowMs))
        while (shots.size > capacity) shots.removeFirst()
        lastMs = nowMs
        this.spentNs += spentNs
        taken++
        if (nowMs - reportedMs >= REPORT_MS) {
            Log.i(TAG, "Frame ring: $taken shots of ${width}x$height, ${this.spentNs / taken / 1000} µs each on average")
            reportedMs = nowMs
            this.spentNs = 0
            taken = 0
        }
    }

    /** The shots of the last [millis] before [nowMs], oldest first, as GIF frames (top row first). */
    @Synchronized
    fun recent(millis: Long, nowMs: Long): List<GifEncoder.Frame> {
        val last = shots.lastOrNull() ?: return emptyList()
        return shots.filter { it.atMs >= nowMs - millis && it.width == last.width && it.height == last.height }.map(::topDown)
    }

    @Synchronized
    fun clear() {
        shots.clear()
        lastMs = 0
    }

    private fun topDown(shot: Shot): GifEncoder.Frame {
        val w = shot.width
        val h = shot.height
        val argb = IntArray(w * h)
        for (y in 0 until h) {
            val row = (h - 1 - y) * w * 4
            for (x in 0 until w) {
                val i = row + x * 4
                argb[y * w + x] = (0xFF shl 24) or ((shot.rgba[i].toInt() and 0xFF) shl 16) or
                    ((shot.rgba[i + 1].toInt() and 0xFF) shl 8) or (shot.rgba[i + 2].toInt() and 0xFF)
            }
        }
        return GifEncoder.Frame(argb, w, h)
    }

    companion object {
        private const val TAG = "FrameRing"
        /** A shot every 100 ms: 10 a second. */
        const val INTERVAL_MS = 100L
        /** Three seconds of shots. */
        const val CAPACITY = 30
        /** Shots this wide (and as high as the picture's shape makes them). */
        const val WIDTH = 480
        private const val REPORT_MS = 60_000L
    }
}
