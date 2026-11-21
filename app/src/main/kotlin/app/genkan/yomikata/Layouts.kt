package app.genkan.yomikata

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** A read a layout couldn't make: outside memory, a missing key, a table it needs. */
class LayoutError(message: String) : Exception(message)

/**
 * Memory as a layout reads it: [size] bytes at [address], or a [LayoutError]. A memory that
 * fails its own way (a binder or socket error, a short reply) must throw a LayoutError too, so
 * the failure lands in that field's errors instead of ending the whole read.
 */
fun interface LayoutMemory {
    fun read(address: Long, size: Int): ByteArray
}

/** What a layout read: each field it names, or why it couldn't (in [errors]). */
data class LayoutRead(
    val message: String? = null,
    /** A name: the stored text, or `speaker_names`' entry for the number. */
    val speakerName: String? = null,
    /** The speaker as a number, when the layout has no name for it. */
    val speakerNumber: Long? = null,
    val shown: Long? = null,
    val waiting: Boolean? = null,
    val choosing: Boolean? = null,
    val choices: List<String>? = null,
    val choiceCount: Long? = null,
    val choiceCursor: Long? = null,
    /** The message as far as it has typed out: [message] without row breaks, first [shown] characters. */
    val onScreen: String? = null,
    val errors: Map<String, String> = emptyMap(),
)

/**
 * The Kotlin reader for an entry's `layouts` (Yomikata docs/format.md, Layouts): where a game
 * keeps what it shows, read from memory with no hook. A port of the reference reader,
 * tools/src/yomikata/layout.py (`read_layout`, `pick`), and checked against its conformance
 * vectors: same fields, same cuts at character boundaries, same cleanup.
 */
object Layouts {

    /** The layout for this version: one that lists [exeSha1], else the first that lists none. */
    fun pick(layouts: List<JSONObject>, exeSha1: String?): JSONObject? {
        var general: JSONObject? = null
        for (layout in layouts) {
            val versions = layout.optJSONArray("versions").strings()
            if (exeSha1 != null && exeSha1 in versions) return layout
            if (versions.isEmpty() && general == null) general = layout
        }
        return general
    }

    /**
     * Everything [layout] names, read from [memory]. A field that can't be read is left out and
     * its reason kept in [LayoutRead.errors]. [unit], [table] and [controls] are the entry's,
     * for `table` text; [cleanup] is the entry's, applied to every text after the text's own.
     */
    fun read(
        layout: JSONObject,
        memory: LayoutMemory,
        unit: Int = 2,
        table: CharTable? = null,
        controls: Map<String, String> = emptyMap(),
        cleanup: List<CleanupStep> = emptyList(),
    ): LayoutRead {
        val order = if (layout.optString("endian", "little") == "big") ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val reader = Reader(memory, order, unit, table, controls, cleanup)
        val errors = LinkedHashMap<String, String>()
        fun <T> attempt(name: String, read: () -> T): T? = try {
            read()
        } catch (e: LayoutError) {
            errors[name] = e.message.orEmpty()
            null
        } catch (e: org.json.JSONException) {
            errors[name] = e.message.orEmpty()
            null
        }

        val message = layout.optJSONObject("message")?.let { spec -> attempt("message") { reader.text(spec) } }
        var speakerName: String? = null
        var speakerNumber: Long? = null
        layout.optJSONObject("speaker")?.let { spec ->
            if (spec.has("charset")) {
                speakerName = attempt("speaker") { reader.text(spec) }
            } else {
                attempt("speaker") { reader.int(spec) }?.let { n ->
                    val name = layout.optJSONObject("speaker_names")?.optString(n.toString())?.takeIf { it.isNotEmpty() }
                    if (name != null) speakerName = name else speakerNumber = n
                }
            }
        }
        fun number(name: String) = layout.optJSONObject(name)?.let { spec -> attempt(name) { reader.int(spec) } }
        fun flag(name: String) = layout.optJSONObject(name)?.let { spec -> attempt(name) { reader.flag(spec) } }
        val shown = number("shown")
        val choiceCount = number("choice_count")
        val choiceCursor = number("choice_cursor")
        val waiting = flag("waiting")
        val choosing = flag("choosing")
        val choices = layout.optJSONObject("choices")?.let { spec ->
            attempt("choices") {
                // An empty text is no answers.
                val rows = reader.text(spec).takeIf { it.isNotEmpty() }?.split("\n").orEmpty()
                if (choiceCount != null) rows.take(choiceCount.coerceAtLeast(0).toInt()) else rows
            }
        }
        val onScreen = if (message != null && shown != null) {
            val flat = message.replace("\n", "")
            // Characters, not UTF-16 units, as the reference slices them.
            val end = flat.offsetByCodePoints(0, minOf(shown.coerceAtLeast(0), flat.codePointCount(0, flat.length).toLong()).toInt())
            flat.substring(0, end)
        } else {
            null
        }
        return LayoutRead(message, speakerName, speakerNumber, shown, waiting, choosing, choices, choiceCount, choiceCursor, onScreen, errors)
    }

    private class Reader(
        val memory: LayoutMemory,
        val order: ByteOrder,
        val unit: Int,
        val table: CharTable?,
        val controls: Map<String, String>,
        val entryCleanup: List<CleanupStep>,
    ) {

        /** The address [spec] names: fixed, or `base` then, for each offset, the pointer there plus it. */
        fun resolve(spec: JSONObject): Long {
            if (spec.has("address")) return hex(spec.getString("address"))
            var address = hex(spec.getString("base"))
            val offsets = spec.getJSONArray("offsets")
            for (i in 0 until offsets.length()) {
                val pointer = ByteBuffer.wrap(read(address, 4)).order(order).int.toLong() and 0xFFFFFFFFL
                address = pointer + hex(offsets.getString(i))
            }
            return address
        }

        fun int(spec: JSONObject): Long {
            val type = spec.optString("type", "u8")
            val size = SIZES[type] ?: throw LayoutError("unknown type $type")
            val buffer = ByteBuffer.wrap(read(resolve(spec), size)).order(order)
            return when (type) {
                "u8" -> buffer.get().toLong() and 0xFF
                "s8" -> buffer.get().toLong()
                "u16" -> buffer.short.toLong() and 0xFFFF
                "s16" -> buffer.short.toLong()
                "u32" -> buffer.int.toLong() and 0xFFFFFFFFL
                else -> buffer.int.toLong()
            }
        }

        fun flag(spec: JSONObject): Boolean {
            val value = int(spec)
            return if (spec.has("equals")) value == spec.getLong("equals") else value != 0L
        }

        fun text(spec: JSONObject): String {
            val charset = spec.getString("charset")
            val width = if (charset == "table") unit else 1
            val terminator = spec.optString("terminator").takeIf { spec.has("terminator") }?.let(::bytes)
            val start = resolve(spec)
            val maxBytes = spec.getInt("max_bytes")
            val rows = spec.optJSONObject("rows")
            val count = rows?.getInt("count") ?: 1
            val stride = rows?.getInt("stride") ?: maxBytes
            var size = minOf(maxBytes, stride)
            // Clamped before it becomes an Int: a u32 length of 0xFFFFFFFF is not -1.
            spec.optJSONObject("length")?.let { size = minOf(size.toLong(), int(it).coerceAtLeast(0)).toInt() }
            val decoder = decoderFor(charset)
            val lines = MutableList(count) { r ->
                decode(cut(read(start + r.toLong() * stride, size), charset, width, terminator), charset, width, decoder)
            }
            if (rows != null) while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
            var text = lines.joinToString("\n")
            // The text's own cleanup, then the entry's, as for a hook.
            for (step in CleanupStep.parse(spec.optJSONArray("cleanup")) + entryCleanup) text = step.apply(text)
            return text
        }

        private fun read(address: Long, size: Int): ByteArray =
            memory.read(address, size).also { if (it.size < size) throw LayoutError("0x%08X+%d is outside memory".format(address, size)) }

        /** Up to the terminator, matched only where a character starts; no character cut in half. */
        private fun cut(data: ByteArray, charset: String, width: Int, terminator: ByteArray?): ByteArray {
            var i = 0
            while (i < data.size) {
                if (terminator != null && i + terminator.size <= data.size && (terminator.indices).all { data[i + it] == terminator[it] }) break
                val n = charLength(data, i, charset, width)
                if (i + n > data.size) break
                i += n
            }
            return data.copyOf(i)
        }

        /** Bytes in the character at [i]: the boundaries terminators and decoding both use. */
        private fun charLength(data: ByteArray, i: Int, charset: String, width: Int): Int {
            val b = data[i].toInt() and 0xFF
            return when (charset) {
                "shift_jis" -> if (b in 0x81..0x9F || b in 0xE0..0xFC) 2 else 1
                "euc_jp" -> if (b == 0x8F) 3 else if (b >= 0x8E) 2 else 1
                "utf8" -> if (b >= 0xF0) 4 else if (b >= 0xE0) 3 else if (b >= 0xC0) 2 else 1
                // A surrogate pair is one character, when both halves are there.
                "utf16le", "utf16be" -> if (i + 4 <= data.size && unit16(data, i, charset) in 0xD800..0xDBFF &&
                    unit16(data, i + 2, charset) in 0xDC00..0xDFFF
                ) 4 else 2
                else -> width
            }
        }

        private fun unit16(data: ByteArray, i: Int, charset: String): Int {
            val a = data[i].toInt() and 0xFF
            val b = data[i + 1].toInt() and 0xFF
            return if (charset == "utf16le") (b shl 8) or a else (a shl 8) or b
        }

        private fun decoderFor(charset: String) = if (charset == "table") {
            null
        } else {
            Charset.forName(CODECS[charset] ?: throw LayoutError("unknown charset $charset")).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        }

        /**
         * [data] as text, one character at a time (the format's rule, so every decoder agrees):
         * a character that doesn't decode is one U+FFFD, and so is any single byte from 0x80 up
         * but Shift-JIS's half-width kana, without asking the decoder.
         */
        private fun decode(data: ByteArray, charset: String, width: Int, decoder: java.nio.charset.CharsetDecoder?): String {
            if (charset == "table") {
                val t = table ?: throw LayoutError("a table charset needs the entry's tables")
                return t.decode(data, unit, controls).trimEnd('\n')
            }
            val out = StringBuilder()
            var i = 0
            while (i < data.size) {
                val n = minOf(charLength(data, i, charset, width), data.size - i)
                val b = data[i].toInt() and 0xFF
                // A single byte from 0x80 up is U+FFFD, except Shift-JIS's half-width kana (A1-DF).
                if (n == 1 && b >= 0x80 && !(charset == "shift_jis" && b in 0xA1..0xDF)) {
                    out.append(REPLACEMENT)
                } else if (charset == "euc_jp" && n == 2 && EUC_JP_FIXED.containsKey((b shl 8) or (data[i + 1].toInt() and 0xFF))) {
                    out.append(EUC_JP_FIXED.getValue((b shl 8) or (data[i + 1].toInt() and 0xFF)))
                } else {
                    try {
                        decoder!!.reset()
                        out.append(decoder.decode(ByteBuffer.wrap(data, i, n)))
                    } catch (e: java.nio.charset.CharacterCodingException) {
                        out.append(REPLACEMENT)
                    }
                }
                i += n
            }
            return out.toString()
        }
    }

    private const val REPLACEMENT = '\uFFFD'

    /**
     * EUC-JP codes whose character differs between decoders (Android's follows Microsoft's
     * eucJP-ms: A1C1 is ～ U+FF5E there), fixed to JIS X 0208's, as the reference's euc_jp gives.
     */
    private val EUC_JP_FIXED = mapOf(
        0xA1BD to '\u2015', 0xA1C0 to '\uFF3C', 0xA1C1 to '\u301C', 0xA1C2 to '\u2016',
        0xA1DD to '\u2212', 0xA1F1 to '\u00A2', 0xA1F2 to '\u00A3', 0xA2CC to '\u00AC',
    )

    private val SIZES = mapOf("u8" to 1, "s8" to 1, "u16" to 2, "s16" to 2, "u32" to 4, "s32" to 4)

    /** Python's codecs as the reference names them (cp932 is Windows' Shift-JIS). */
    private val CODECS = mapOf(
        "shift_jis" to "windows-31j", "euc_jp" to "EUC-JP", "utf16le" to "UTF-16LE", "utf16be" to "UTF-16BE", "utf8" to "UTF-8",
    )

    private fun hex(text: String): Long = text.removePrefix("0x").removePrefix("0X").toLong(16)

    private fun bytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun JSONArray?.strings(): List<String> = this?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
}
