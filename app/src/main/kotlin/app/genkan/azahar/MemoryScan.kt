package app.genkan.azahar

import java.nio.charset.Charset

/** Where a piece of text was found in emulated memory, and how it was encoded there. */
data class TextHit(val address: Long, val charset: Charset)

/** Searching emulated memory for text the player can see on screen. */
object MemoryScan {

    val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")

    /** 3DS games mostly keep text as UTF-16; some as UTF-8, older ports as Shift-JIS. */
    val CHARSETS: List<Charset> = listOf(Charsets.UTF_16LE, Charsets.UTF_8, SHIFT_JIS)

    /**
     * The game's data areas: its heap, and the linear heap at both the old address and the one
     * newer system versions map it at. Unmapped stretches read back as zeros.
     */
    val REGIONS: List<LongRange> = listOf(
        0x08000000L until 0x10000000L,
        0x14000000L until 0x1C000000L,
        0x30000000L until 0x40000000L,
    )

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

    fun inRegions(address: Long): Boolean = REGIONS.any { address in it }

    /** [raw] as shown: control characters (line breaks, colour codes' markers) dropped. */
    fun clean(raw: String): String = raw.filter { it >= ' ' && it != '\uFFFF' }.trim()

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
     * Aligned little-endian u32 slots in [chunk] (read at [base]) whose value is in [targets]:
     * pointers into the string, as slot address to value.
     */
    fun pointersInto(chunk: ByteArray, base: Long, targets: LongRange): List<Pair<Long, Long>> {
        val hits = mutableListOf<Pair<Long, Long>>()
        var i = ((4 - (base % 4).toInt()) % 4)
        while (i + 4 <= chunk.size) {
            val value = (chunk[i].toLong() and 0xFF) or ((chunk[i + 1].toLong() and 0xFF) shl 8) or
                ((chunk[i + 2].toLong() and 0xFF) shl 16) or ((chunk[i + 3].toLong() and 0xFF) shl 24)
            if (value in targets) hits += (base + i) to value
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
