package app.genkan.memory

import app.genkan.yomikata.CharTable

/**
 * Reading a game whose text is in its own codes (a Yomikata table, [CharTable]) out of memory,
 * with OCR's help: the table knows the kana, but a game like Moonlight Syndrome numbers its
 * kanji by each scene's font, which no table can list. So the line OCR read is found by its
 * kana codes, the codes there are lined up with what OCR read, and the line is given with the
 * table's kana (exact, where OCR misreads) and, for each code the table lacks, the character
 * OCR read in its place, which is learned for the scene's later lines.
 */
class TableText(private val table: CharTable, private val unit: Int = 2) {

    /** One code in memory: a character the table knows, one it doesn't (a kanji), or a control. */
    sealed interface Token {
        data class Known(val text: String) : Token
        data class Unknown(val code: String) : Token
        data class Control(val code: String) : Token
    }

    /** Characters seen for each unknown code, and how often: the most seen is the code's. */
    private val votes = HashMap<String, HashMap<Char, Int>>()

    /** What OCR's characters taught so far: code to character. */
    fun learned(): Map<String, Char> = votes.mapValues { (_, v) -> v.maxBy { it.value }.key }

    /**
     * The codes of stretches of [line] that are all in the table, [PROBE] characters each, from
     * along its runs: what to look for in memory. Short, since a row break in the text box is a
     * control code in memory that OCR doesn't see.
     */
    fun probes(line: String): List<ByteArray> {
        val runs = mutableListOf<String>()
        var run = StringBuilder()
        for (c in line) {
            if (table.code(c) != null) run.append(c) else { runs += run.toString(); run = StringBuilder() }
        }
        runs += run.toString()
        val pieces = runs.filter { it.length >= PROBE }.sortedByDescending { it.length }.flatMap { r ->
            (0..r.length - PROBE step PROBE).map { r.substring(it, it + PROBE) }
        }
        return pieces.take(MAX_PROBES).map { piece ->
            piece.map { table.code(it)!! }.joinToString("").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }

    /** [bytes] as codes from [from] (any alignment: a game's buffer can start at an odd address). */
    fun tokens(bytes: ByteArray, from: Int, until: Int): List<Token> {
        val out = mutableListOf<Token>()
        var i = from
        while (i + unit <= until) {
            val code = buildString { for (k in 0 until unit) append("%02X".format(bytes[i + k].toInt() and 0xFF)) }
            val text = table.char(code)
            // The code's value, for telling controls apart: Human's are 0x8000 up (0xFFF9, 0x8100).
            val value = (if (table.littleEndian) code.chunked(2).reversed().joinToString("") else code).toLong(16)
            val high = 1L shl (unit * 8 - 1)
            out += when {
                text != null -> Token.Known(text)
                table.isBreak(code) || value >= high || value == 0L -> Token.Control(code)
                else -> Token.Unknown(code)
            }
            i += unit
        }
        return out
    }

    /**
     * [ocr] as the game wrote it, from the codes around where its kana were found ([tokens]),
     * or null when they don't line up with it. Learns the unknown codes' characters on the way.
     */
    fun correct(tokens: List<Token>, ocr: String): String? {
        val o = ocr.filterNot { it.isWhitespace() }
        // Controls are where the game breaks rows and lines: not characters OCR sees.
        val at = tokens.indices.filter { tokens[it] !is Token.Control }
        val t = at.map { tokens[it] }
        if (o.isEmpty() || t.isEmpty()) return null
        val known = learned()
        fun cost(i: Int, j: Int): Double = when (val token = t[j]) {
            is Token.Known -> if (token.text == o[i].toString()) 0.0 else 1.0
            is Token.Unknown -> when {
                known[token.code] == o[i] -> 0.0
                known[token.code] != null -> 0.8
                isKanji(o[i]) -> 0.2 // an unknown code is most likely a kanji
                else -> 0.7
            }
            is Token.Control -> 1.0
        }
        // Semi-global alignment: OCR's whole line against any stretch of the codes.
        val n = o.length
        val m = t.size
        val d = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) d[i][0] = i.toDouble()
        for (i in 1..n) for (j in 1..m) {
            d[i][j] = minOf(d[i - 1][j - 1] + cost(i - 1, j - 1), d[i - 1][j] + GAP, d[i][j - 1] + GAP)
        }
        val end = (0..m).minBy { d[n][it] }
        if (d[n][end] > n * MAX_COST) return null
        // Back along the path: each code's text, or for an unknown code, what OCR read for it.
        val out = StringBuilder()
        val taught = mutableListOf<Pair<String, Char>>()
        var i = n
        var j = end
        while (i > 0 && j > 0) {
            val diagonal = d[i - 1][j - 1] + cost(i - 1, j - 1)
            when {
                d[i][j] == diagonal -> {
                    when (val token = t[j - 1]) {
                        is Token.Known -> out.append(token.text.reversed())
                        is Token.Unknown -> {
                            // What earlier lines agreed on beats a new reading; one reading alone doesn't.
                            val seen = o[i - 1]
                            val c = known[token.code]?.takeIf { it == seen || sure(token.code) || !isKanji(seen) }
                                ?: seen.takeIf { isKanji(it) || it in PUNCTUATION }
                            // Anything else OCR saw there (the box's ▼) isn't the game's text.
                            c?.let(out::append)
                            if (isKanji(seen) || seen in PUNCTUATION) taught += token.code to seen
                        }
                        is Token.Control -> Unit
                    }
                    i--; j--
                }
                d[i][j] == d[i - 1][j] + GAP -> i-- // OCR saw something the game didn't write: a misread mark
                else -> {
                    // A code OCR missed: kept when it's text the table knows.
                    (t[j - 1] as? Token.Known)?.let { out.append(it.text.reversed()) }
                    j--
                }
            }
        }
        for ((code, c) in taught) votes.getOrPut(code) { HashMap() }.merge(c, 1, Int::plus)
        if (j == end) return null
        // Out to the line's own ends, the controls either side: what OCR missed or misread
        // there (the dots before a line, read as "o.") is the game's text too.
        fun text(token: Token) = when (token) {
            is Token.Known -> token.text
            is Token.Unknown -> learned()[token.code]?.toString().orEmpty()
            is Token.Control -> ""
        }
        var first = at[j]
        while (first > 0 && tokens[first - 1] !is Token.Control) first--
        var last = at[end - 1]
        while (last + 1 < tokens.size && tokens[last + 1] !is Token.Control) last++
        val before = (first until at[j]).joinToString("") { text(tokens[it]) }
        val after = (at[end - 1] + 1..last).joinToString("") { text(tokens[it]) }
        return (before + out.reverse().toString() + after).takeIf { it.isNotEmpty() }
    }

    private fun sure(code: String): Boolean = (votes[code]?.values?.maxOrNull() ?: 0) >= SURE

    private fun isKanji(c: Char) = c in '一'..'鿿' || c == '々'

    companion object {
        const val PROBE = 5
        /** Marks a game codes like characters, which OCR reads well enough to learn. */
        private const val PUNCTUATION = "。、！？「」『』…ー〜（）・"
        private const val SURE = 2
        const val MAX_PROBES = 6
        private const val GAP = 1.0
        /** At most this much cost a character for the codes to be OCR's line. */
        private const val MAX_COST = 0.35
    }
}
