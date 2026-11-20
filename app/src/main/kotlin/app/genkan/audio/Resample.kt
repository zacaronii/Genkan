package app.genkan.audio

/** Sample-rate conversion for speech clips: linear interpolation is plenty for a card. */
object Resample {
    fun linear(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val size = (input.size.toLong() * toRate / fromRate).toInt()
        val step = fromRate.toDouble() / toRate
        return ShortArray(size) { i ->
            val position = i * step
            val index = position.toInt().coerceAtMost(input.size - 1)
            val next = (index + 1).coerceAtMost(input.size - 1)
            val fraction = position - index
            (input[index] + (input[next] - input[index]) * fraction).toInt().toShort()
        }
    }
}
