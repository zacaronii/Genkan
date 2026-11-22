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
) {
    /**
     * Waiting for a press on a line. Genkan's own rule, not the format's: a window can read as
     * waiting for a frame or two as it opens, with nothing typed in it yet, and that waits on nothing.
     */
    val waitingOnLine: Boolean get() = waiting == true && !message.isNullOrBlank()

    /**
     * A question on screen. Genkan's own rule, not the format's: `choosing` can read true as any
     * window opens, before its rows are typed, and with no answers to choose from it is no question.
     */
    val asking: Boolean get() = choosing == true && !choices.isNullOrEmpty()
}

/**
 * The Kotlin reader for an entry's `layouts` (Yomikata docs/format.md, Layouts): where a game
 * keeps what it shows, read from memory with no hook. A port of the reference reader,
 * tools/src/yomikata/layout.py (`read_layout`, `pick`), and checked against its conformance
 * vectors: same fields, same cuts at character boundaries, same cleanup.
 */
object Layouts {

    /** The layout for this version: one that lists [exeSha1], else the first that lists none. */
    fun pick(layouts: List<JSONObject>, exeSha1: String?): JSONObject? = layoutsFor(layouts, exeSha1).firstOrNull()

    /**
     * A version's layouts, in the entry's order: those naming [exeSha1], else those naming no
     * versions (docs/format.md, "Which layout"). Usually one; a game that loads a program per
     * chapter has one per program, and [liveOf] picks among them on every read.
     */
    fun layoutsFor(layouts: List<JSONObject>, exeSha1: String?): List<JSONObject> {
        // A layout with a key this reader doesn't know is passed over, not half-read.
        val known = layouts.filter(::understood)
        return known.filter { exeSha1 != null && exeSha1 in it.optJSONArray("versions").strings() }
            .ifEmpty { known.filter { it.optJSONArray("versions").strings().isEmpty() } }
    }

    /** One layout, or a JSON array of a version's layouts, as a list. */
    fun parseLayouts(json: String): List<JSONObject> {
        val text = json.trim()
        if (text.isEmpty()) return emptyList()
        return if (text.startsWith("[")) JSONArray(text).let { a -> List(a.length()) { a.getJSONObject(it) } } else listOf(JSONObject(text))
    }

    /** [layoutsFor] for a release, as [pick] does with an entry. */
    fun layoutsFor(entry: CatalogEntry, releaseId: String?): List<JSONObject> = layoutsFor(entry.layouts, versionFor(entry, releaseId))

    /**
     * Which of a version's [layouts] to read now: the first whose own `when` is true, or that has
     * none; one whose `when` can't be read is passed over. With none true, the first: reading it
     * gives what a false `when` gives.
     */
    fun liveOf(layouts: List<JSONObject>, memory: LayoutMemory): JSONObject? =
        layouts.firstOrNull { live(it, memory) != false } ?: layouts.firstOrNull()

    /**
     * The layout's own `when` as it reads now: null when the layout has none, false when it is
     * false or can't be read (nothing of the layout is to be taken then).
     */
    fun live(layout: JSONObject, memory: LayoutMemory): Boolean? {
        val spec = layout.optJSONObject("when") ?: return null
        val order = if (layout.optString("endian", "little") == "big") ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        return try {
            Reader(memory, order, 2, null, emptyMap(), emptyList()).flag(spec)
        } catch (e: LayoutError) {
            false
        } catch (e: org.json.JSONException) {
            false
        }
    }

    /**
     * Whether this reader knows every key and named value (charset, int type, `counts`) in
     * [layout]. A newer key can change what the others mean (a text without its `when` would show
     * what is no longer on screen), so a layout this reader doesn't fully know is treated as not
     * there (docs/format.md, "Layouts"; Yomikata's `understood`).
     */
    fun understood(layout: JSONObject): Boolean {
        if (!layout.keySet().all { it in FIELDS || it in OTHER_KEYS }) return false
        if (layout.optString("endian", "little") !in setOf("little", "big")) return false
        if (layout.has("when") && !numberOk(layout.optJSONObject("when"), NUMBER_KEYS)) return false
        if (layout.has("tables") && !switchOk(layout.optJSONObject("tables"))) return false
        for ((key, kind) in FIELDS) {
            if (!layout.has(key)) continue
            val spec = layout.optJSONObject(key) ?: return false
            val isText = kind == "text" || (kind == "either" && spec.has("charset"))
            if (!(if (isText) textOk(spec) else numberOk(spec, NUMBER_KEYS))) return false
        }
        return true
    }

    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()

    private fun numberOk(spec: JSONObject?, keys: Set<String>, counts: Set<String> = setOf("bytes", "characters")): Boolean =
        spec != null && spec.keySet().all { it in keys } && spec.optString("type", "u8") in SIZES &&
            spec.optString("counts", "bytes") in counts &&
            (!spec.has("mask") || spec.opt("mask").let { (it is Int && it >= 0) || (it is Long && it >= 0) })

    private fun textOk(spec: JSONObject): Boolean {
        if (!spec.keySet().all { it in TEXT_KEYS } || spec.optString("charset") !in CODECS.keys + "table") return false
        if (spec.has("length") && !numberOk(spec.optJSONObject("length"), LENGTH_KEYS)) return false
        if (spec.has("when") && !numberOk(spec.optJSONObject("when"), NUMBER_KEYS)) return false
        if (spec.has("codes")) {
            // Each lead 1-4 whole hex bytes; each length an integer from the lead's own up to 16.
            val codes = spec.optJSONObject("codes") ?: return false
            if (codes.length() == 0) return false
            for (lead in codes.keys()) {
                val n = codes.opt(lead)
                if (!LEAD.matches(lead) || n !is Int || n < lead.length / 2 || n > 16) return false
            }
        }
        if (spec.has("script")) {
            // Only with a table, never with rows, pages, a terminator or a length; open is whole
            // hex bytes and offset a number.
            val script = spec.optJSONObject("script") ?: return false
            if (spec.optString("charset") != "table" || listOf("rows", "pages", "terminator", "length").any(spec::has)) return false
            // offset, and optionally open; texts (whose only value is "rows") and rest need open.
            if (!script.has("offset") || !setOf("open", "offset", "texts", "rest").containsAll(script.keySet())) return false
            if (!script.has("open") && (script.has("texts") || script.has("rest"))) return false
            if (script.has("rest") && script.opt("rest").let { it !is Int || it !in 1..16 }) return false
            if (script.has("texts") && script.opt("texts") != "rows") return false
            if (script.has("open") && (script.opt("open") as? String)?.let { HEX_BYTES.matches(it) } != true) return false
            // The offset counts bytes, or codes of the entry's unit.
            if (!numberOk(script.optJSONObject("offset"), LENGTH_KEYS, setOf("bytes", "codes"))) return false
        }
        if (spec.has("pages")) {
            // Pages and rows never go together; the break is whole hex bytes.
            val pages = spec.optJSONObject("pages") ?: return false
            if (spec.has("rows") || pages.keySet() != setOf("break", "cursor")) return false
            if ((pages.opt("break") as? String)?.let { HEX_BYTES.matches(it) } != true) return false
            if (!numberOk(pages.optJSONObject("cursor"), WHERE + "type")) return false
        }
        if (!spec.has("rows")) return true
        val rows = spec.optJSONObject("rows") ?: return false
        if (!rows.keySet().all { it in setOf("count", "stride", "lengths", "pointers") }) return false
        // pointers may only be true, and not with lengths (a pointed-to text has no length cell).
        if (rows.has("pointers") && (rows.opt("pointers") != true || rows.has("lengths"))) return false
        return !rows.has("lengths") || numberOk(rows.optJSONObject("lengths"), LENGTH_KEYS)
    }

    private val WHERE = setOf("address", "base", "offsets")
    private val NUMBER_KEYS = WHERE + setOf("type", "equals", "mask")
    private val LENGTH_KEYS = WHERE + setOf("type", "counts")
    private val TEXT_KEYS = WHERE + setOf("charset", "max_bytes", "terminator", "length", "rows", "pages", "script", "codes", "when", "cleanup")
    private val LEAD = Regex("([0-9A-Fa-f]{2}){1,4}")
    private val HEX_BYTES = Regex("([0-9A-Fa-f]{2})+")
    private val FIELDS = mapOf(
        "message" to "text", "choices" to "text", "speaker" to "either", "shown" to "number", "waiting" to "number",
        "choosing" to "number", "choice_count" to "number", "choice_cursor" to "number",
    )
    private val OTHER_KEYS = setOf("versions", "endian", "poll", "speaker_names", "notes", "when", "tables")

    /** A layout's own `tables`: a number `by`, and for each value (decimal) a list of table files. */
    private fun switchOk(spec: JSONObject?): Boolean {
        if (spec == null || spec.keySet() != setOf("by", "sets") || !numberOk(spec.optJSONObject("by"), WHERE + "type")) return false
        val sets = spec.optJSONObject("sets") ?: return false
        if (sets.length() == 0) return false
        return sets.keys().asSequence().all { n ->
            val paths = sets.optJSONArray(n)
            SET_NUMBER.matches(n) && paths != null && paths.length() > 0 && (0 until paths.length()).all { paths.opt(it) is String }
        }
    }

    private val SET_NUMBER = Regex("-?[0-9]{1,10}")

    /** The table files every layout of [entry] may switch to (its `tables.sets`), for fetching. */
    fun setTablePaths(layouts: List<JSONObject>): Set<String> = layouts.flatMap { layout ->
        val sets = layout.optJSONObject("tables")?.optJSONObject("sets") ?: return@flatMap emptyList()
        sets.keys().asSequence().flatMap { n -> sets.optJSONArray(n)?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty() }.toList()
    }.filter { it.isNotEmpty() }.toSet()

    /**
     * The layout for a release, for a reader that can't hash its executable (Genkan can't):
     * the `exe_sha1` of the only version [entry] lists for [releaseId] (a version without an id
     * is the entry's own) stands in for the hash, and [pick] chooses as usual. With several
     * versions of that id, or none, only a layout naming no versions. Yomikata's `pick` with a
     * release id (docs/format.md, "Which layout").
     */
    fun pick(entry: CatalogEntry, releaseId: String?, exeSha1: String? = null): JSONObject? =
        pick(entry.layouts, exeSha1 ?: versionFor(entry, releaseId))

    /** The `exe_sha1` of the only version [entry] lists for [releaseId], or null. */
    fun versionFor(entry: CatalogEntry, releaseId: String?): String? {
        val mine = entry.versions.filter { (it.id ?: entry.id) == releaseId }
        return mine.singleOrNull()?.exeSha1
    }

    /**
     * Everything [layout] names, read from [memory]. A field that can't be read is left out and
     * its reason kept in [LayoutRead.errors]. [unit], [table] and [controls] are the entry's,
     * for `table` text; [cleanup] is the entry's, applied to every text after the text's own.
     * A layout with its own `tables` decodes with [withSet] instead: the entry's tables and then
     * the files of the set its number names (null when they aren't all there).
     */
    fun read(
        layout: JSONObject,
        memory: LayoutMemory,
        unit: Int = 2,
        table: CharTable? = null,
        controls: Map<String, String> = emptyMap(),
        cleanup: List<CleanupStep> = emptyList(),
        withSet: ((List<String>) -> CharTable?)? = null,
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

        // The layout's own `when`: while false (outside the game's script, where the same memory
        // holds something else) nothing else is read. Texts are empty, flags false, numbers left out.
        layout.optJSONObject("when")?.let { spec ->
            val live = attempt("when") { reader.flag(spec) } ?: return LayoutRead(errors = errors)
            if (!live) {
                fun text(name: String) = "".takeIf { layout.optJSONObject(name)?.has("charset") == true }
                fun flag(name: String) = false.takeIf { layout.has(name) }
                return LayoutRead(
                    message = text("message"),
                    speakerName = text("speaker"),
                    waiting = flag("waiting"),
                    choosing = flag("choosing"),
                    choices = emptyList<String>().takeIf { layout.has("choices") },
                )
            }
        }

        // The set of tables the layout's number names, after the entry's. No set, or a number that
        // can't be read: no table at all, so table texts fail rather than read wrong characters.
        layout.optJSONObject("tables")?.let { switch ->
            val more = try {
                switch.getJSONObject("sets").optJSONArray(reader.int(switch.getJSONObject("by")).toString())
            } catch (e: LayoutError) {
                null
            }
            reader.table = more?.let { a -> List(a.length()) { a.getString(it) } }?.let { withSet?.invoke(it) }
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

    /** [read] as JSON, the reference's keys (a speaker number under `speaker` too). */
    fun toJson(read: LayoutRead): JSONObject = JSONObject().apply {
        read.message?.let { put("message", it) }
        read.speakerName?.let { put("speaker", it) }
        read.speakerNumber?.let { put("speaker", it) }
        read.shown?.let { put("shown", it) }
        read.choiceCount?.let { put("choice_count", it) }
        read.choiceCursor?.let { put("choice_cursor", it) }
        read.waiting?.let { put("waiting", it) }
        read.choosing?.let { put("choosing", it) }
        read.choices?.let { put("choices", JSONArray(it)) }
        read.onScreen?.let { put("on_screen", it) }
        if (read.errors.isNotEmpty()) put("errors", JSONObject(read.errors as Map<*, *>))
    }

    /** What [toJson] wrote, read back. */
    fun fromJson(json: JSONObject): LayoutRead {
        val speaker = json.opt("speaker")
        val errors = json.optJSONObject("errors")
        return LayoutRead(
            message = json.optString("message").takeIf { json.has("message") },
            speakerName = speaker as? String,
            speakerNumber = (speaker as? Number)?.toLong(),
            shown = json.optLong("shown").takeIf { json.has("shown") },
            waiting = json.optBoolean("waiting").takeIf { json.has("waiting") },
            choosing = json.optBoolean("choosing").takeIf { json.has("choosing") },
            choices = json.optJSONArray("choices")?.strings(),
            choiceCount = json.optLong("choice_count").takeIf { json.has("choice_count") },
            choiceCursor = json.optLong("choice_cursor").takeIf { json.has("choice_cursor") },
            onScreen = json.optString("on_screen").takeIf { json.has("on_screen") },
            errors = errors?.let { e -> e.keys().asSequence().associateWith { e.optString(it) } }.orEmpty(),
        )
    }

    private class Reader(
        val memory: LayoutMemory,
        val order: ByteOrder,
        val unit: Int,
        var table: CharTable?,
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

        /** The number [spec] names; [index] picks one of a run of them, each right after the last. */
        fun int(spec: JSONObject, index: Int = 0): Long {
            val type = spec.optString("type", "u8")
            val size = SIZES[type] ?: throw LayoutError("unknown type $type")
            val buffer = ByteBuffer.wrap(read(resolve(spec) + index.toLong() * size, size)).order(order)
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
            // A mask keeps only those bits, of the value as stored: an s8's -1 under 3 is 3.
            val value = if (spec.has("mask")) int(spec) and spec.getLong("mask") else int(spec)
            return if (spec.has("equals")) value == spec.getLong("equals") else value != 0L
        }

        fun text(spec: JSONObject): String {
            // What the memory holds isn't on screen now: nothing else of the text is read.
            spec.optJSONObject("when")?.let { if (!flag(it)) return "" }
            val charset = spec.getString("charset")
            // The game's own codes in the text (a colour, a style): each lead's whole length.
            codes = spec.optJSONObject("codes")?.let { c -> c.keys().asSequence().map { bytes(it) to c.getInt(it) }.toList() }.orEmpty()
            val width = if (charset == "table") unit else 1
            spec.optJSONObject("script")?.let { script -> return scriptText(spec, script) }
            val terminator = spec.optString("terminator").takeIf { spec.has("terminator") }?.let(::bytes)
            val start = resolve(spec)
            val maxBytes = spec.getInt("max_bytes")
            val rows = spec.optJSONObject("rows")
            val count = rows?.getInt("count") ?: 1
            val stride = rows?.getInt("stride") ?: maxBytes
            val length = spec.optJSONObject("length")
            val rowLengths = rows?.optJSONObject("lengths")
            // Each row a 32-bit pointer to its text, `stride` apart; the text runs to its terminator or max_bytes.
            val pointers = rows?.opt("pointers") == true
            val decoder = decoderFor(charset)
            val lines = MutableList(count) { r ->
                var size = if (pointers) maxBytes else minOf(maxBytes, stride)
                var chars: Long? = null
                // `length` holds for every row, `rows.lengths` for its own; where both are given, both hold.
                for ((lengthSpec, index) in listOf(length to 0, rowLengths to r)) {
                    if (lengthSpec == null) continue
                    // Clamped before it becomes an Int: a u32 length of 0xFFFFFFFF is not -1.
                    val n = int(lengthSpec, index).coerceAtLeast(0)
                    if (lengthSpec.optString("counts", "bytes") == "characters") {
                        chars = minOf(chars ?: n, n)
                    } else {
                        size = minOf(size.toLong(), n).toInt()
                    }
                }
                var at = start + r.toLong() * stride
                if (pointers) {
                    at = java.nio.ByteBuffer.wrap(read(at, 4)).order(order).int.toLong() and 0xFFFFFFFFL
                    // A null pointer is an empty row.
                    if (at == 0L) return@MutableList ""
                }
                var raw = cut(read(at, size), charset, width, terminator, chars)
                // A buffer of pages walked by the game's cursor: only the page the cursor is on.
                spec.optJSONObject("pages")?.let { pages ->
                    raw = page(raw, charset, width, bytes(pages.getString("break")), int(pages.getJSONObject("cursor")) - start)
                }
                decode(raw, charset, width, decoder)
            }
            if (rows != null) while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
            var text = lines.joinToString("\n")
            // The text's own cleanup, then the entry's, as for a hook.
            for (step in cleanupOf(spec) + entryCleanup) text = step.apply(text)
            return text
        }

        /**
         * A text read straight out of a script (docs/format.md, "With `script`"): the page the
         * game's position is on, as far as it has read it.
         */
        private fun scriptText(spec: JSONObject, script: JSONObject): String {
            val t = table ?: throw LayoutError("no table to decode with")
            val start = resolve(spec)
            val offset = script.getJSONObject("offset")
            val at = int(offset) * if (offset.optString("counts") == "codes") unit else 1
            val size = maxOf(0L, minOf(spec.getInt("max_bytes").toLong(), at)).toInt()
            // Without open, the script is one text from its start.
            val mark = script.optString("open").takeIf { script.has("open") }?.let(::bytes)
            val ends = HashSet<String>()
            val breaks = HashSet<String>()
            for ((code, what) in controls) {
                if (what == "end") ends += code
                if (what == "page" || what == "clear") breaks += code
            }
            val rows = script.optString("texts") == "rows"
            // At or before the script's start there is nothing to read.
            val isEnd = { c: String -> t.isEnd(c) || c in ends }
            val texts = when {
                size <= 0 -> emptyList()
                mark != null -> scriptPage(read(start + at - size, size + mark.size), size, mark, isEnd, breaks, rows, script.optInt("rest"))
                // One text from the start: only when what's read reaches back to it.
                size.toLong() == at -> pagesFrom(read(start, size), 0, null, isEnd, breaks, rows = false)
                else -> emptyList()
            }
            var text = texts.joinToString("\n") { t.decode(it, unit, controls, codes).trimEnd('\n') }
            for (step in cleanupOf(spec) + entryCleanup) text = step.apply(text)
            return text
        }

        /**
         * The page in [both] ([size] bytes of script up to the position, then as many as [mark]),
         * in one piece, or with [rows] a piece for each of its texts: a text starts after a mark
         * and stops at an end code; a mark right after an end code with no page break before it
         * carries the page on. Nothing when the position isn't in a text, or a text is about to
         * start a new page. With [rest], a position up to that many codes past an end code is read
         * as just past it. A port of the reference's `_script`.
         */
        private fun scriptPage(
            read: ByteArray,
            readSize: Int,
            mark: ByteArray,
            isEnd: (String) -> Boolean,
            breaks: Set<String>,
            rows: Boolean,
            /** How many codes past an end code the game may rest while its text stays up (0: none). */
            rest: Int = 0,
        ): List<ByteArray> {
            var both = read
            var size = readSize
            // The nearest end code behind the position, k codes back: the position is read as just past it.
            for (k in 0..(if (rest > 0) rest else -1)) {
                val at = size - (k + 1) * unit
                if (at >= 0 && isEnd(hexOf(both, at, unit))) {
                    size -= k * unit
                    both = both.copyOf(size + mark.size)
                    break
                }
            }
            val data = both.copyOf(size)
            val ahead = both.copyOfRange(size, both.size)
            fun code(i: Int): String = if (i >= 0 && i + unit <= data.size) hexOf(data, i, unit) else ""
            fun markAt(p: Int) = p >= mark.size && mark.indices.all { data[p - mark.size + it] == mark[it] }
            fun ends(i: Int) = code(i).let { it.isNotEmpty() && isEnd(it) }
            // A mark ending at q carries the page on.
            fun joins(q: Int) = ends(q - unit) && code(q - 2 * unit) !in breaks
            var p = data.size
            var start = -1
            if (ahead.contentEquals(mark) && !joins(p)) return emptyList()
            // The text has just ended.
            if (ends(p - unit)) p -= unit
            while (true) {
                while (p >= mark.size && !markAt(p) && !ends(p - unit)) p -= unit
                // Out of what was read, or a second end code: not a text.
                if (p < mark.size || !markAt(p)) break
                start = p
                val q = p - mark.size
                if (!joins(q)) break
                // On through the text before.
                p = q - unit
            }
            if (start < 0) return emptyList()
            return pagesFrom(data, start, mark, isEnd, breaks, rows)
        }

        /**
         * What a script holds from [start] to its end ([data]), less the pages that are off the
         * screen; with a [mark], two texts of the page join where an end code meets it (each a
         * piece of its own with [rows]).
         */
        private fun pagesFrom(data: ByteArray, start: Int, mark: ByteArray?, isEnd: (String) -> Boolean, breaks: Set<String>, rows: Boolean): List<ByteArray> {
            fun code(i: Int): String = if (i >= 0 && i + unit <= data.size) hexOf(data, i, unit) else ""
            fun ends(i: Int) = code(i).let { it.isNotEmpty() && isEnd(it) }
            // Where the end codes at the position begin: a page code with only those after it ended a
            // page that is still up.
            var tail = data.size
            while (tail - unit >= start && ends(tail - unit)) tail -= unit
            var pieces = mutableListOf(java.io.ByteArrayOutputStream())
            var i = start
            while (i + unit <= data.size) {
                val n = codeLength(data, i)
                if (n == 0 && mark != null && ends(i) && i + unit + mark.size <= data.size && mark.indices.all { data[i + unit + it] == mark[it] }) {
                    // Where two texts of the page join: with rows, the next text is a row of its own.
                    i += unit + mark.size
                    if (rows) pieces += java.io.ByteArrayOutputStream()
                    continue
                }
                if (n == 0 && code(i) in breaks && i + unit < tail) {
                    // A new page: what came before it is off the screen, and the code goes with it.
                    pieces = mutableListOf(java.io.ByteArrayOutputStream())
                    i += unit
                    continue
                }
                val take = if (n > 0) n else unit
                pieces.last().write(data, i, minOf(take, data.size - i))
                i += take
            }
            return pieces.map { it.toByteArray() }
        }

        private fun hexOf(data: ByteArray, i: Int, n: Int) = (i until i + n).joinToString("") { "%02X".format(data[it].toInt() and 0xFF) }

        private fun read(address: Long, size: Int): ByteArray =
            memory.read(address, size).also { if (it.size < size) throw LayoutError("0x%08X+%d is outside memory".format(address, size)) }

        /**
         * Up to the terminator, matched only where a character starts; no character cut in half;
         * at most [chars] characters (a table code is one), when a length counts characters.
         */
        private fun cut(data: ByteArray, charset: String, width: Int, terminator: ByteArray?, chars: Long? = null): ByteArray {
            var i = 0
            var count = 0L
            while (i < data.size && (chars == null || count < chars)) {
                if (terminator != null && i + terminator.size <= data.size && (terminator.indices).all { data[i + it] == terminator[it] }) break
                val n = charLength(data, i, charset, width)
                if (i + n > data.size) break
                i += n
                count++
            }
            return data.copyOf(i)
        }

        /**
         * The page a script cursor is on: [data] split at each [mark] that starts a character;
         * the last page with anything in it that starts at or before [cursor] (an offset into
         * [data]). A cursor outside the text is on its last page with anything in it: a game may
         * drop its cursor once the last page has typed out.
         */
        private fun page(data: ByteArray, charset: String, width: Int, mark: ByteArray, cursor: Long): ByteArray {
            val pages = mutableListOf<Pair<Int, Int>>()
            var start = 0
            var i = 0
            while (i < data.size) {
                if (mark.isNotEmpty() && i + mark.size <= data.size && mark.indices.all { data[i + it] == mark[it] }) {
                    pages += start to i
                    i += mark.size
                    start = i
                } else {
                    i += charLength(data, i, charset, width)
                }
            }
            pages += start to data.size
            val inside = cursor in 0..data.size.toLong()
            val full = pages.filter { (a, b) -> b > a && (!inside || a <= cursor) }
            return full.lastOrNull()?.let { (a, b) -> data.copyOfRange(a, minOf(b, data.size)) } ?: ByteArray(0)
        }

        /** The current text's codes (lead bytes, whole length): one character each, decoded to nothing. */
        private var codes: List<Pair<ByteArray, Int>> = emptyList()

        /** Bytes in the code starting at [i], or 0 when none starts there: the longest lead wins. */
        private fun codeLength(data: ByteArray, i: Int): Int =
            codes.filter { (lead, _) -> i + lead.size <= data.size && lead.indices.all { data[i + it] == lead[it] } }
                .maxByOrNull { it.first.size }?.second ?: 0

        /** Bytes in the character at [i]: the boundaries terminators and decoding both use. */
        private fun charLength(data: ByteArray, i: Int, charset: String, width: Int): Int {
            // A code and its arguments are one character.
            codeLength(data, i).takeIf { it > 0 }?.let { return it }
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
                val t = table ?: throw LayoutError("no table to decode with")
                return t.decode(data, unit, controls, codes).trimEnd('\n')
            }
            val out = StringBuilder()
            var i = 0
            while (i < data.size) {
                // A code is nothing in the text.
                val code = codeLength(data, i)
                if (code > 0) {
                    i += code
                    continue
                }
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

    /** A text's cleanup, compiled once: a layout is read every frame. */
    private val cleanups = java.util.concurrent.ConcurrentHashMap<String, List<CleanupStep>>()

    private fun cleanupOf(spec: JSONObject): List<CleanupStep> {
        val steps = spec.optJSONArray("cleanup") ?: return emptyList()
        return cleanups.getOrPut(steps.toString()) { CleanupStep.parse(steps) }
    }

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
