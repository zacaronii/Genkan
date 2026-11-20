package app.genkan.memory

import app.genkan.ppsspp.PspHooks
import java.nio.charset.Charset

/** Where a piece of text was found in emulated memory, and how it was encoded there. */
data class TextHit(val address: Long, val charset: Charset)

/** Searching emulated memory for text the player can see on screen. */
object MemoryScan {

    val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")

    /** 3DS games mostly keep text as UTF-16, PS1 and GBA games Shift-JIS, newer ones UTF-8. */
    val CHARSETS: List<Charset> = listOf(Charsets.UTF_16LE, Charsets.UTF_8, SHIFT_JIS)

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
        val step = if (charset == Charsets.UTF_16LE) 2 else 1
        var at = offset
        while (at - step >= 0) {
            val previous = at - step
            val stop = if (step == 2) {
                !isTextChar(((bytes[previous].toInt() and 0xFF) or ((bytes[previous + 1].toInt() and 0xFF) shl 8)).toChar())
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

    /** [raw] as shown: markup tags and control characters (line breaks, colour codes' markers) dropped. */
    fun clean(raw: String): String = raw.replace(TAG, "").filter { it >= ' ' && it != '\uFFFF' }.trim()

    /** A page of a game's script: where its bytes start in what was read, and what it shows. */
    data class Page(val offset: Int, val text: String)

    /**
     * [bytes] of a script split into the pages the player sees: at page tags (`<PAGE>`) and at
     * terminators, each cleaned of markup. Pages that show nothing are left out.
     */
    fun pages(bytes: ByteArray, charset: Charset): List<Page> {
        val width = if (charset == Charsets.UTF_16LE) 2 else 1
        val breaks = PAGE_BREAKS.map { it.toByteArray(charset) }
        val pages = mutableListOf<Page>()
        var start = 0
        var i = 0
        fun close(end: Int, skip: Int) {
            val text = clean(PspHooks.decode(bytes.copyOfRange(start, end), charset))
            if (text.isNotEmpty()) pages += Page(start, text)
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
        return pages
    }

    /**
     * Whether [memory] is the string OCR saw as [ocr]: most of what OCR read is in it, in order,
     * and it isn't a much longer text that merely contains those characters.
     */
    fun matches(memory: String, ocr: String): Boolean {
        val seen = ocr.filter { !it.isWhitespace() }
        val text = clean(memory).filter { !it.isWhitespace() }
        if (seen.length < 2 || text.length > seen.length * 3 + 8) return false
        return commonLength(text, seen) >= seen.length * 0.7
    }

    /**
     * Aligned little-endian u32 slots in [chunk] (read at [base]) that point into [targets]
     * (after [target] folds the value to an address): pointers into the string, as slot
     * address to the address pointed at.
     */
    fun pointersInto(
        chunk: ByteArray,
        base: Long,
        targets: LongRange,
        target: (Long) -> Long? = { it },
    ): List<Pair<Long, Long>> {
        val hits = mutableListOf<Pair<Long, Long>>()
        var i = ((4 - (base % 4).toInt()) % 4)
        while (i + 4 <= chunk.size) {
            val value = (chunk[i].toLong() and 0xFF) or ((chunk[i + 1].toLong() and 0xFF) shl 8) or
                ((chunk[i + 2].toLong() and 0xFF) shl 16) or ((chunk[i + 3].toLong() and 0xFF) shl 24)
            if (value != 0L) target(value)?.takeIf { it in targets }?.let { hits += (base + i) to it }
            i += 4
        }
        return hits
    }

    fun u32(bytes: ByteArray): Long = (bytes[0].toLong() and 0xFF) or ((bytes[1].toLong() and 0xFF) shl 8) or
        ((bytes[2].toLong() and 0xFF) shl 16) or ((bytes[3].toLong() and 0xFF) shl 24)

    /** Characters [a] and [b] share in order (longest common subsequence), over the longer length. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return commonLength(a, b).toDouble() / maxOf(a.length, b.length)
    }

    /** Markup in game scripts: `<E003 20>`, `<PAGE>`, `<CNTR>`. */
    private val TAG = Regex("<[A-Za-z][A-Za-z0-9_ ]*>")

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
