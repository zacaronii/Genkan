package app.genkan.memory

/**
 * Finds a line in memory when the game's encoding is unknown, by its shape: which of its
 * characters repeat, and where. A run like 「ていました」 has its own pattern of equal and
 * different characters, and so do the game's codes for it, whatever numbers the game uses.
 * This is the ROM hacker's relative search, for building a game's table (Yomikata samples).
 */
object TextShape {

    /** A place the codes have the line's shape: [unit] bytes per code, in either byte order. */
    data class Hit(val offset: Int, val unit: Int, val bigEndian: Boolean, val length: Int)

    /**
     * The part of [line] to look for: the stretch of kana and kanji, at most [MAX_RUN]
     * characters, whose characters repeat the most, and only with [MIN_REPEATS] repeats or more:
     * a shape with fewer matches random bytes all over memory. Punctuation is left out: OCR
     * misreads it most, and games often code it apart.
     */
    fun probe(line: String): String? = probes(line).firstOrNull()

    /**
     * Stretches to try in turn, the most repetitive first, each starting somewhere else: OCR
     * joins a text box's rows, and a stretch across a row break isn't in memory as one run
     * (the game keeps a newline code there).
     */
    fun probes(line: String, count: Int = MAX_PROBES): List<String> {
        val runs = line.split(Regex("[^\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}ー々]+")).filter { it.length >= MIN_RUN }
        val windows = runs.flatMapIndexed { r, run ->
            (0..run.length - MIN_RUN).flatMap { from ->
                (MIN_RUN..minOf(MAX_RUN, run.length - from)).map { length -> Triple(r, from, run.substring(from, from + length)) }
            }
        }.filter { repeats(it.third) >= MIN_REPEATS }
            .sortedWith(compareByDescending<Triple<Int, Int, String>> { repeats(it.third) }.thenByDescending { it.third.length })
        val chosen = mutableListOf<Triple<Int, Int, String>>()
        for (w in windows) {
            if (chosen.size >= count) break
            if (chosen.none { it.first == w.first && kotlin.math.abs(it.second - w.second) < MIN_RUN }) chosen += w
        }
        return chosen.map { it.third }
    }

    /** How many of [text]'s characters are ones seen before in it. */
    private fun repeats(text: String): Int = text.length - text.toSet().size

    /** Every place in [bytes] whose 1- or 2-byte codes have [probe]'s shape; zeros never match. */
    fun find(bytes: ByteArray, probe: String): List<Hit> {
        val shape = shapeOf(probe)
        val n = shape.size
        val hits = mutableListOf<Hit>()
        val seen = IntArray(n)
        for ((unit, bigEndian) in LAYOUTS) {
            val last = bytes.size - n * unit
            for (start in 0..last step 1) {
                if (unit == 2 && start % 2 != 0 && !ODD_ALIGNED) continue
                var ok = true
                var distinct = 0
                for (k in 0 until n) {
                    val v = code(bytes, start + k * unit, unit, bigEndian)
                    if (v == 0) { ok = false; break }
                    val want = shape[k]
                    if (want == distinct) {
                        // A new character: its code must differ from every one seen so far.
                        for (j in 0 until distinct) if (seen[j] == v) { ok = false; break }
                        if (!ok) break
                        seen[distinct++] = v
                    } else if (seen[want] != v) {
                        ok = false; break
                    }
                }
                if (ok) hits += Hit(start, unit, bigEndian, n * unit)
            }
        }
        return hits
    }

    /** "ていました" → [0, 1, 2, 3, 4]; "ああいあ" → [0, 0, 1, 0]. */
    fun shapeOf(text: String): IntArray {
        val ids = HashMap<Char, Int>()
        return IntArray(text.length) { ids.getOrPut(text[it]) { ids.size } }
    }

    private fun code(bytes: ByteArray, at: Int, unit: Int, bigEndian: Boolean): Int {
        val a = bytes[at].toInt() and 0xFF
        if (unit == 1) return a
        val b = bytes[at + 1].toInt() and 0xFF
        return if (bigEndian) (a shl 8) or b else (b shl 8) or a
    }

    private val LAYOUTS = listOf(1 to false, 2 to false, 2 to true)

    /** 2-byte codes are searched at even addresses only, as the CPUs these games ran on need. */
    private const val ODD_ALIGNED = false
    private const val MIN_RUN = 5
    private const val MIN_REPEATS = 2
    private const val MAX_PROBES = 4
    const val MAX_RUN = 16
}
