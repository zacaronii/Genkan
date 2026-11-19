// SPDX-FileCopyrightText: Furoshiki contributors
// SPDX-License-Identifier: GPL-2.0-or-later
// Adapted from Furoshiki home/src/line_feed.{h,cpp} (sommepo/furoshiki @ 738ccf5): Normalise,
// IsJunk, Key, Compare, MessageText and Feed, ported from Qt to Kotlin.

package app.genkan.reader

import com.squareup.moshi.JsonReader
import okio.Buffer

/**
 * What a texthooker sent, made into lines: one line of Japanese per message, a line written
 * piece by piece handed over once, the same line again dropped.
 */
object Lines {

    /** Kana, kanji (with the rare and the compatibility ones) and half-width kana. */
    private fun isJapanese(c: Int) =
        c in 0x3040..0x30FF || c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF ||
            c in 0xF900..0xFAFF || c in 0xFF66..0xFF9F || c in 0x20000..0x2FA1F

    private fun hasJapanese(text: String) = text.codePoints().anyMatch(::isJapanese)

    /** Spaces and line breaks are layout: one line, with no space beside a Japanese character. */
    fun normalise(text: String): String {
        // Runs of spaces and line breaks become one space, none at the ends.
        val spaced = buildString {
            var space = false
            for (c in text.trim { it.isWhitespace() }) {
                if (c.isWhitespace()) {
                    space = true
                } else {
                    if (space) append(' ')
                    space = false
                    append(c)
                }
            }
        }
        if (!hasJapanese(spaced)) return spaced
        // Japanese has no spaces between words: one beside a character that is not ASCII is layout.
        return buildString {
            spaced.forEachIndexed { i, c ->
                if (c == ' ') {
                    val before = i > 0 && spaced[i - 1].code > 0x7f
                    val after = i + 1 < spaced.length && spaced[i + 1].code > 0x7f
                    if (before || after) return@forEachIndexed
                }
                append(c)
            }
        }
    }

    /** Nothing Japanese in it: stray marks, numbers, letters. */
    fun isJunk(text: String) = !hasJapanese(text)

    private const val MARKS = "、。，．,.!！?？「」『』…・〜ー-"

    /** The text with spaces and punctuation aside, as two lines are compared. */
    fun key(text: String) = text.filterNot { it.isWhitespace() || it in MARKS }

    enum class Verdict {
        NEW,
        /** The previous line again. */
        SAME,
        /** The previous line with more after it: text that was still being written. */
        CARRIES_ON,
        /** The beginning of the previous line. */
        PART_OF,
        /** Empty, or nothing Japanese. */
        JUNK,
    }

    /** What [next] is, given the line before it (empty: none). */
    fun compare(previous: String, next: String): Verdict {
        if (next.isEmpty() || isJunk(next)) return Verdict.JUNK
        if (previous.isEmpty()) return Verdict.NEW
        val a = key(previous)
        val b = key(next)
        return when {
            a == b -> Verdict.SAME
            a.isNotEmpty() && b.startsWith(a) -> Verdict.CARRIES_ON
            b.isNotEmpty() && a.startsWith(b) -> Verdict.PART_OF
            else -> Verdict.NEW
        }
    }

    /**
     * The text of one WebSocket message: the frame itself, or the "sentence" (else "text") of
     * a frame that is a JSON object. A JSON object without either is no line ("").
     */
    fun messageText(frame: String): String {
        val trimmed = frame.trim()
        if (!trimmed.startsWith('{')) return frame
        val fields = jsonStringFields(trimmed) ?: return frame
        return fields["sentence"] ?: fields["text"] ?: ""
    }

    /** The top-level string fields of a JSON object, or null when it isn't one. */
    internal fun jsonStringFields(json: String): Map<String, String>? = try {
        val reader = JsonReader.of(Buffer().writeUtf8(json))
        val out = HashMap<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (reader.peek() == JsonReader.Token.STRING) out[name] = reader.nextString() else reader.skipValue()
        }
        reader.endObject()
        if (reader.peek() != JsonReader.Token.END_DOCUMENT) null else out
    } catch (e: Exception) {
        null
    }
}

/**
 * The lines of one source in order. A line waits a moment before it is handed over (settle),
 * so that text sent piece by piece as a game writes it is one line; the same line again is
 * dropped; a line that carries on one already handed over takes that one back.
 */
class LineFeed {

    data class Out(
        val text: String,
        /** A line handed over before, of which this is the whole: to be taken back. */
        val takesBack: String? = null,
    )

    private var pending = ""
    private var pendingAt = 0L
    private var pendingTakesBack: String? = null
    private var handed = ""
    private var handedAt = 0L

    val hasPending: Boolean get() = pending.isNotEmpty()

    /**
     * Text arrived at [now] (ms). Returns what must be handed over at once (the line that
     * waited, when this is another line); the text itself waits until [settle].
     */
    fun take(raw: String, now: Long): List<Out> {
        val out = ArrayList<Out>(1)
        val text = Lines.normalise(raw)
        val waiting = pending.isNotEmpty()
        val before = if (waiting) pending else handed
        val beforeAt = if (waiting) pendingAt else handedAt
        var verdict = Lines.compare(before, text)
        if ((verdict == Lines.Verdict.CARRIES_ON || verdict == Lines.Verdict.PART_OF) && now - beforeAt > CARRY_MS) {
            verdict = Lines.Verdict.NEW
        }
        when (verdict) {
            Lines.Verdict.JUNK, Lines.Verdict.SAME, Lines.Verdict.PART_OF -> Unit
            Lines.Verdict.CARRIES_ON -> {
                if (!waiting) pendingTakesBack = handed
                pending = text
                pendingAt = now
            }
            Lines.Verdict.NEW -> {
                if (waiting) settle(now)?.let(out::add)
                pending = text
                pendingAt = now
                pendingTakesBack = null
            }
        }
        return out
    }

    /** The wait is over: the line that waited, if any. */
    fun settle(now: Long): Out? {
        if (pending.isEmpty()) return null
        val out = Out(pending, pendingTakesBack)
        handed = pending
        handedAt = now
        pending = ""
        pendingTakesBack = null
        return out
    }

    fun reset() {
        pending = ""
        pendingAt = 0
        pendingTakesBack = null
        handed = ""
        handedAt = 0
    }

    companion object {
        /** How long a line waits for more of itself. */
        const val SETTLE_MS = 150L

        /**
         * Within this of the line before, a line that carries it on replaces it and a line
         * that is its beginning is dropped. Later, both are lines of their own: はい after
         * はい、行こう is a line.
         */
        const val CARRY_MS = 8000L
    }
}
