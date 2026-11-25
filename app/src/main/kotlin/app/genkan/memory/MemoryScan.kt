package app.genkan.memory

import app.genkan.ppsspp.PspHooks
import java.nio.charset.Charset

/** Where a piece of text was found in emulated memory, and how it was encoded there. */
data class TextHit(val address: Long, val charset: Charset)

/** Searching emulated memory for text the player can see on screen. */
object MemoryScan {

    val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")
    val EUC_JP: Charset = Charset.forName("EUC-JP")

    /**
     * The encodings text is looked for in: 3DS games mostly keep text as UTF-16, PS1, GBA and
     * GameCube games Shift-JIS, newer ones UTF-8, a few EUC-JP. UTF-16 in the memory's own byte
     * order ([bigEndian]: a GameCube's).
     */
    fun charsets(bigEndian: Boolean): List<Charset> =
        listOf(if (bigEndian) Charsets.UTF_16BE else Charsets.UTF_16LE, Charsets.UTF_8, SHIFT_JIS, EUC_JP)

    /** The bytes each character of [charset] takes at least: 2 for UTF-16, which is aligned to them. */
    fun unit(charset: Charset): Int = if (charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE) 2 else 1

    /** Offsets of every [needle] occurrence in [haystack]; UTF-16 hits only on even offsets. */
    fun find(haystack: ByteArray, needle: ByteArray, aligned: Boolean = false): List<Int> {
        if (needle.isEmpty() || needle.size > haystack.size) return emptyList()
        val hits = mutableListOf<Int>()
        val first = needle[0]
        var i = 0
        val last = haystack.size - needle.size
        while (i <= last) {
            if (haystack[i] == first && (!aligned || i % 2 == 0)) {
                var j = 1
                while (j < needle.size && haystack[i + j] == needle[j]) j++
                if (j == needle.size) hits += i
            }
            i++
        }
        return hits
    }

    /**
     * Pieces of an OCR'd [line] to look for: OCR misreads the odd character, so whole lines
     * rarely match, but a few short runs from different places in it usually do.
     */
    fun probes(line: String, length: Int = 4): List<String> {
        val text = line.filter { !it.isWhitespace() }
        if (text.length <= length) return listOfNotNull(text.takeIf { it.length >= 2 })
        val starts = listOf(0, (text.length - length) / 2, text.length - length).distinct()
        return starts.map { text.substring(it, it + length) }
    }

    /**
     * The start of the string around [offset]: back to the previous terminator, or for UTF-16
     * to the first character that can't be text (games often put a record's header, such as
     * addresses, right before its string with no terminator between).
     */
    fun stringStart(bytes: ByteArray, offset: Int, charset: Charset): Int {
        val step = unit(charset)
        var at = offset
        while (at - step >= 0) {
            val previous = at - step
            val stop = if (step == 2) {
                val (low, high) = if (charset == Charsets.UTF_16BE) previous + 1 to previous else previous to previous + 1
                !isTextChar(((bytes[low].toInt() and 0xFF) or ((bytes[high].toInt() and 0xFF) shl 8)).toChar())
            } else {
                bytes[previous] == 0.toByte()
            }
            if (stop) break
            at = previous
        }
        return at
    }

    /** Japanese text, ASCII, their punctuation and full-width forms, and control codes (not NUL). */
    fun isTextChar(c: Char): Boolean = c != '\u0000' && (
        c < '\u007F' || c in '\u2000'..'\u206F' || c in '\u2160'..'\u2BFF' || c in '\u3000'..'\u30FF' ||
            c in '\u4E00'..'\u9FFF' || c in '\uFF00'..'\uFFEF'
        )

    /** No rules of the game's own. */
    val NO_RULES: (String) -> String = { it }

    /**
     * [raw] as shown: first the game's own [rules] (its catalog entry's cleanup, for codes of its
     * engine), then markup tags and control characters (line breaks, colour codes' markers)
     * dropped, and furigana (`[奇跡/きせき]`, Layton's) reduced to the kanji under it.
     */
    fun clean(raw: String, rules: (String) -> String = NO_RULES): String =
        rules(raw).replace(TAG, "").replace(FURIGANA, "$1").filter { it >= ' ' && it != '\uFFFF' }.trim()

    /** A page of a game's script: where its bytes start in what was read, and what it shows. */
    data class Page(val offset: Int, val text: String)

    /**
     * [bytes] of a script split into the pages the player sees: at page tags (`<PAGE>`) and at
     * terminators, each cleaned of markup. Pages that show nothing are left out.
     */
    fun pages(bytes: ByteArray, charset: Charset, rules: (String) -> String = NO_RULES): List<Page> =
        spans(bytes, charset, rules).map { Page(it.offset, it.text) }

    /**
     * The run of adjacent pages in [bytes] that reads most like OCR's [line], joined, with the
     * stray characters at its ends OCR didn't see trimmed off; and how alike the two are. Some
     * games keep each line of a text box as a string of its own, a few control bytes from the
     * next, whose bytes also decode as stray characters (`ｽK` before a line, `’` after it).
     */
    fun bestRun(bytes: ByteArray, charset: Charset, line: String, rules: (String) -> String = NO_RULES): Pair<Page, Double>? {
        val seen = line.filter { !it.isWhitespace() }
        if (seen.isEmpty()) return null
        val spans = spans(bytes, charset, rules)
        var best: Pair<Page, Double>? = null
        for (first in spans.indices) {
            var text = ""
            for (last in first until minOf(spans.size, first + MAX_RUN)) {
                if (last > first && spans[last].offset - spans[last - 1].end > RUN_GAP) break
                // Each string's own strays come off, or they'd end up between the lines.
                text += trimStrays(spans[last].text, seen)
                // Far longer than what OCR saw: more strings only make it worse.
                if (text.length > seen.length * 2 + 8) break
                val score = similarity(text.filter { !it.isWhitespace() }, seen)
                if (score > (best?.second ?: 0.0)) best = Page(spans[first].offset, text) to score
            }
        }
        return best
    }

    /** [text] without the characters at either end that aren't Japanese and OCR didn't see. */
    /**
     * How many lines of a script [bytes] hold: strings with some Japanese in them. More than one
     * right after a line means the game keeps its script as a table of lines.
     */
    fun scriptLines(bytes: ByteArray, charset: Charset): Int =
        spans(bytes, charset).count { span -> span.text.count(::isJapanese) >= MIN_SCRIPT_LINE }

    internal fun trimStrays(text: String, seen: String): String {
        fun stray(c: Char) = !isJapanese(c) && c !in seen
        return text.dropWhile(::stray).dropLastWhile(::stray)
    }

    /** Kana, kanji, Japanese and full-width punctuation (not half-width katakana). */
    private fun isJapanese(c: Char): Boolean =
        c in '\u3000'..'\u30FF' || c in '\u4E00'..'\u9FFF' || c in '\uFF01'..'\uFF5E' || c == '…' || c == '‥'

    /** A page and where its bytes end. */
    private class Span(val offset: Int, val end: Int, val text: String)

    private fun spans(bytes: ByteArray, charset: Charset, rules: (String) -> String = NO_RULES): List<Span> {
        val width = unit(charset)
        val breaks = PAGE_BREAKS.map { it.toByteArray(charset) }
        val spans = mutableListOf<Span>()
        var start = 0
        var i = 0
        fun close(end: Int, skip: Int) {
            val text = clean(PspHooks.decode(bytes.copyOfRange(start, end), charset), rules)
            if (text.isNotEmpty()) spans += Span(start, end, text)
            start = end + skip
        }
        while (i < bytes.size) {
            val match = breaks.firstOrNull { b -> i + b.size <= bytes.size && (0 until b.size).all { bytes[i + it] == b[it] } }
            when {
                match != null -> { close(i, match.size); i += match.size }
                i + width <= bytes.size && (0 until width).all { bytes[i + it] == 0.toByte() } -> { close(i, width); i += width }
                else -> i += width
            }
        }
        close(bytes.size, 0)
        return spans
    }

    /**
     * Whether [memory] is the string OCR saw as [ocr]: most of what OCR read is in it, in order,
     * and it isn't a much longer text that merely contains those characters.
     */
    fun matches(memory: String, ocr: String, rules: (String) -> String = NO_RULES): Boolean {
        val seen = ocr.filter { !it.isWhitespace() }
        val text = clean(memory, rules).filter { !it.isWhitespace() }
        if (seen.length < 2 || text.length > seen.length * 3 + 8) return false
        return commonLength(text, seen) >= seen.length * 0.7
    }

    /**
     * Aligned u32 slots in [chunk] (read at [base]) that point into [targets] (after [target]
     * folds the value to an address): pointers into the string, as slot address to the address
     * pointed at. Little-endian, or [bigEndian] (a GameCube's).
     */
    fun pointersInto(
        chunk: ByteArray,
        base: Long,
        targets: LongRange,
        bigEndian: Boolean = false,
        target: (Long) -> Long? = { it },
    ): List<Pair<Long, Long>> {
        val hits = mutableListOf<Pair<Long, Long>>()
        var i = ((4 - (base % 4).toInt()) % 4)
        while (i + 4 <= chunk.size) {
            val value = u32(chunk, i, bigEndian)
            if (value != 0L) target(value)?.takeIf { it in targets }?.let { hits += (base + i) to it }
            i += 4
        }
        return hits
    }

    /** The u32 at [at] in [bytes], little-endian or [bigEndian]. */
    fun u32(bytes: ByteArray, at: Int = 0, bigEndian: Boolean = false): Long {
        fun b(k: Int) = bytes[at + (if (bigEndian) 3 - k else k)].toLong() and 0xFF
        return b(0) or (b(1) shl 8) or (b(2) shl 16) or (b(3) shl 24)
    }

    /** Characters [a] and [b] share in order (longest common subsequence), over the longer length. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return commonLength(a, b).toDouble() / maxOf(a.length, b.length)
    }

    /** Markup in game scripts: `<E003 20>`, `<PAGE>`, `<CNTR>`, Layton's `<M9/1/1>`. */
    private val TAG = Regex("<[A-Za-z][A-Za-z0-9_ /]*>")

    /** Ruby written inline as `[base/reading]`. */
    private val FURIGANA = Regex("""\[([^\[\]/]+)/[^\[\]]*]""")

    /** Japanese characters a string needs to count as a line of a script. */
    private const val MIN_SCRIPT_LINE = 4

    /** At most this many adjacent strings make one text box. */
    private const val MAX_RUN = 4

    /** Strings at most this many bytes apart can be lines of one text box. */
    private const val RUN_GAP = 16

    /** Tags that end a page of a script. */
    private val PAGE_BREAKS = listOf("<PAGE>")

    /** Length of the longest common subsequence of [a] and [b]. */
    private fun commonLength(a: String, b: String): Int {
        val previous = IntArray(b.length + 1)
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                current[j] = if (a[i - 1] == b[j - 1]) previous[j - 1] + 1 else maxOf(previous[j], current[j - 1])
            }
            current.copyInto(previous)
        }
        return previous[b.length]
    }
}
