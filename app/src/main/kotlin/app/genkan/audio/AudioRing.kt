package app.genkan.audio

/**
 * The last [seconds] of audio, with the wall-clock time of its newest sample, so a clip can be
 * cut by the times lines arrived. Not thread-safe; [GameAudioRecorder] guards it.
 */
class AudioRing(private val sampleRate: Int, seconds: Int) {

    private val buffer = ShortArray(sampleRate * seconds)
    private var written = 0L
    private var endsAt = 0L

    /** [count] samples from [data] ending at [now] (ms). */
    fun write(data: ShortArray, count: Int, now: Long) {
        for (i in 0 until count) buffer[((written + i) % buffer.size).toInt()] = data[i]
        written += count
        endsAt = now
    }

    /** The audio between [fromMs] and [toMs] (wall clock), as far as the ring still holds it. */
    fun clip(fromMs: Long, toMs: Long): ShortArray {
        if (written == 0L || toMs <= fromMs) return ShortArray(0)
        val oldest = written - minOf(written, buffer.size.toLong())
        fun index(ms: Long) = (written - (endsAt - ms) * sampleRate / 1000).coerceIn(oldest, written)
        val from = index(fromMs)
        val to = index(toMs)
        return ShortArray((to - from).toInt()) { buffer[((from + it) % buffer.size).toInt()] }
    }
}
