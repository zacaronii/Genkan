package app.genkan.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Cuts the silence off both ends of a voice clip. Frames of [FRAME_MS] count as sound when
 * their loudness is within [RANGE_DB] of the clip's loudest frame; the clip keeps
 * [PAD_MS] either side of the sound. A stand-in for a voice detector: game music under the
 * voice stays, but the silence before and after a line goes.
 */
object ClipTrimmer {

    const val FRAME_MS = 20
    const val RANGE_DB = 30.0
    const val PAD_MS = 150

    /** Loudness floor below which a whole clip is silence (no audio was captured). */
    const val SILENT_DBFS = -55.0

    /** The trimmed clip, or null when it is silence throughout. */
    fun trim(samples: ShortArray, sampleRate: Int): ShortArray? {
        val frame = sampleRate * FRAME_MS / 1000
        if (frame == 0 || samples.size < frame) return null
        val levels = DoubleArray(samples.size / frame) { dbfs(samples, it * frame, frame) }
        val peak = levels.maxOrNull() ?: return null
        if (peak < SILENT_DBFS) return null
        val floor = peak - RANGE_DB
        val first = levels.indexOfFirst { it >= floor }
        val last = levels.indexOfLast { it >= floor }
        val pad = sampleRate * PAD_MS / 1000
        val start = max(0, first * frame - pad)
        val end = minOf(samples.size, (last + 1) * frame + pad)
        return samples.copyOfRange(start, end)
    }

    /** Loudness of [length] samples from [offset], in dB below full scale. */
    fun dbfs(samples: ShortArray, offset: Int, length: Int): Double {
        var sum = 0.0
        for (i in offset until offset + length) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / length)
        return if (rms <= 0) -120.0 else 20 * log10(rms)
    }
}
