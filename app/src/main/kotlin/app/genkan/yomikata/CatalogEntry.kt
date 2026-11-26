package app.genkan.yomikata

import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * One release's reading rules from the Yomikata catalog (docs/format.md there): its encoding,
 * and for games with their own codes, a character table, control codes and cleanup. PSP entries
 * may carry [hooks], the code addresses each line passes through. [tuning] is the built-in
 * player's step for the release, by core id (see [app.genkan.core.TuningLadders]).
 */
data class CatalogEntry(
    val id: String,
    val platform: String,
    val title: String,
    val encoding: String,
    val status: String,
    val versions: List<Version> = emptyList(),
    val tables: List<String> = emptyList(),
    val unit: Int = 1,
    val controls: Map<String, String> = emptyMap(),
    val cleanup: List<CleanupStep> = emptyList(),
    val hooks: List<Hook> = emptyList(),
    val tuning: Map<String, app.genkan.core.BundledTuning> = emptyMap(),
    /** A Wii game's controller (app.genkan.core.WiiController's choices); null: not said, the Nunchuk. */
    val wiiController: String? = null,
) {
    /** A printing the entry is known to fit: a boot executable's hash (discs) or a header CRC (cartridges). */
    data class Version(val id: String?, val exeSha1: String?, val headerCrc32: String?)

    data class Hook(val address: Long, val register: String, val charset: String, val versions: List<String>, val cleanup: List<CleanupStep>)

    /** Whether an executable with [sha1] is one the entry was made for; true when the entry lists none. */
    fun fits(sha1: String): Boolean = versions.none { it.exeSha1 != null } || versions.any { it.exeSha1 == sha1.lowercase() }

    /** A [hook]'s line after its own cleanup, then the entry's; null when only spaces are left (a filtered line). */
    fun clean(raw: String, hook: Hook? = null): String? {
        var text = raw
        for (step in hook?.cleanup.orEmpty() + cleanup) text = step.apply(text)
        return text.takeIf { it.isNotBlank() }
    }

    companion object {
        fun parse(json: String): CatalogEntry {
            val o = JSONObject(json)
            return CatalogEntry(
                id = o.getString("id"),
                platform = o.getString("platform"),
                title = o.optString("title"),
                encoding = o.getString("encoding"),
                status = o.optString("status"),
                versions = o.optJSONArray("versions").objects().map {
                    Version(it.optStringOrNull("id"), it.optStringOrNull("exe_sha1"), it.optStringOrNull("header_crc32"))
                },
                tables = o.optJSONArray("tables").strings(),
                unit = o.optInt("unit", 1),
                controls = o.optJSONObject("controls")?.let { c -> c.keys().asSequence().associate { it.uppercase() to c.getString(it) } }.orEmpty(),
                cleanup = CleanupStep.parse(o.optJSONArray("cleanup")),
                hooks = o.optJSONArray("hooks").objects().map(::parseHook),
                tuning = o.optJSONObject("tuning")?.let { t ->
                    t.keys().asSequence().mapNotNull { core ->
                        val step = t.optJSONObject(core) ?: return@mapNotNull null
                        val id = step.optStringOrNull("step") ?: return@mapNotNull null
                        core to app.genkan.core.BundledTuning(id, step.optStringOrNull("measured_on"))
                    }.toMap()
                }.orEmpty(),
                wiiController = app.genkan.core.WiiController.of(o.optStringOrNull("wii_controller")),
            )
        }

        /** One hook, as an entry lists it. */
        fun parseHook(o: JSONObject) = Hook(
            address = o.getString("address").removePrefix("0x").removePrefix("0X").toLong(16),
            register = o.getString("register"),
            charset = o.getString("charset"),
            versions = o.optJSONArray("versions").strings(),
            cleanup = CleanupStep.parse(o.optJSONArray("cleanup")),
        )

        private fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()
        private fun JSONArray?.strings(): List<String> = this?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    }
}

/**
 * One cleanup step, declarative so every client gives the same text: a regex replacement, where
 * `$1`…`$9` insert a group and `$$` is a dollar sign, or single-character remaps. It matches the
 * catalog's reference implementation (tools/src/yomikata/cleanup.py): Unicode classes, as
 * Python's `\s` and `\w` are, and a group that didn't take part inserts nothing.
 */
sealed interface CleanupStep {
    fun apply(text: String): String

    data class Replace(val regex: Regex, val replacement: String) : CleanupStep {
        override fun apply(text: String): String = regex.replace(text) { expand(replacement, it) }
    }

    data class Remap(val map: Map<Char, String>) : CleanupStep {
        override fun apply(text: String): String = buildString { text.forEach { append(map[it] ?: it) } }
    }

    companion object {
        fun parse(steps: JSONArray?): List<CleanupStep> = steps?.let { a ->
            List(a.length()) { i ->
                val s = a.getJSONObject(i)
                if (s.has("remap")) {
                    val m = s.getJSONObject("remap")
                    Remap(m.keys().asSequence().filter { it.length == 1 }.associate { it[0] to m.getString(it) })
                } else {
                    Replace(compile(s.getString("regex")), s.getString("replace"))
                }
            }
        }.orEmpty()

        private fun compile(pattern: String): Regex = try {
            Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS).toRegex()
        } catch (e: IllegalArgumentException) {
            // A regex engine without the flag (ICU's classes are Unicode already).
            Regex(pattern)
        }

        /** The catalog's replacement for one match: `$1`…`$9` a group (empty if unmatched), `$$` a dollar sign. */
        internal fun expand(replacement: String, match: MatchResult): String = buildString {
            var i = 0
            while (i < replacement.length) {
                val c = replacement[i]
                val next = replacement.getOrNull(i + 1)
                when {
                    c == '$' && next == '$' -> { append('$'); i++ }
                    c == '$' && next != null && next in '1'..'9' -> { append(match.groups.takeIf { next - '0' < it.size }?.get(next - '0')?.value.orEmpty()); i++ }
                    else -> append(c)
                }
                i++
            }
        }
    }
}

/**
 * A Thingy character table (`0100=学`, `/FFFF` an end code, `*FFFE` a newline), keys in memory
 * byte order. Several files apply in order, later ones winning, as the catalog lists them.
 */
class CharTable private constructor(
    private val chars: Map<String, String>,
    private val ends: Set<String>,
    private val newlines: Set<String>,
) {
    val size: Int get() = chars.size

    /** What [code] (hex, memory byte order) stands for, or null when the table lacks it. */
    fun char(code: String): String? = chars[code.uppercase()]

    /**
     * Whether the table's codes look little-endian in memory: most of a 2-byte table's keys
     * then end in 00 (あ = 01 00), where a big-endian one's begin with it (00 50).
     */
    val littleEndian: Boolean by lazy {
        val two = chars.keys.filter { it.length == 4 }
        two.count { it.endsWith("00") } > two.count { it.startsWith("00") }
    }

    /** Whether [code] (hex, memory byte order) is one the tables mark as an end (`/`). */
    fun isEnd(code: String): Boolean = code.uppercase() in ends

    /** Whether [code] ends a string or a line. */
    fun isBreak(code: String): Boolean = code.uppercase().let { it in ends || it in newlines }

    /**
     * The table's codes of one width as ints ([packed]): a layout is read every few frames, and
     * a string per code made reading a big text cost tens of milliseconds.
     */
    private class Packed(val chars: HashMap<Int, String>, val ends: HashSet<Int>, val newlines: HashSet<Int>)

    private val packedByUnit = java.util.concurrent.ConcurrentHashMap<Int, Packed>()

    private fun packed(unit: Int): Packed = packedByUnit.getOrPut(unit) {
        val width = unit * 2
        fun ints(keys: Collection<String>) = keys.filter { it.length == width }.mapTo(HashSet()) { it.toLong(16).toInt() }
        Packed(
            chars.entries.filter { it.key.length == width }.associateTo(HashMap()) { it.key.toLong(16).toInt() to it.value },
            ints(ends),
            ints(newlines),
        )
    }

    /** Whether the code [packed] from [unit] bytes is one the tables mark as an end. */
    fun isEndCode(code: Int, unit: Int): Boolean = unit <= 4 && code in packed(unit).ends

    /** The last controls map given to [decode], packed: the same map comes on every read. */
    @Volatile
    private var packedControls: Triple<Map<String, String>, Int, Map<Int, String>>? = null

    private fun packedControls(controls: Map<String, String>, unit: Int): Map<Int, String> {
        packedControls?.let { (map, u, ints) -> if (map === controls && u == unit) return ints }
        val ints = controls.entries.filter { it.key.length == unit * 2 }.associate { it.key.toLong(16).toInt() to it.value }
        packedControls = Triple(controls, unit, ints)
        return ints
    }

    private val reverse: Map<Char, String> by lazy {
        buildMap { chars.entries.sortedBy { it.key }.forEach { (code, text) -> if (text.length == 1) putIfAbsent(text[0], code) } }
    }

    /** The code for [c] (the lowest, when several stand for it), or null. */
    fun code(c: Char): String? = reverse[c]

    /**
     * [bytes] as text, [unit] bytes per code: ends and page breaks become blank lines, newlines
     * line breaks, other [controls] nothing, and codes the table lacks U+FFFD. [codes] (lead
     * bytes, whole length with arguments) are looked for before any of these, and are nothing.
     */
    fun decode(
        bytes: ByteArray,
        unit: Int,
        controls: Map<String, String> = emptyMap(),
        codes: List<Pair<ByteArray, Int>> = emptyList(),
    ): String = if (unit in 1..4) decodePacked(bytes, unit, controls, codes) else buildString {
        var i = 0
        while (i + unit <= bytes.size) {
            // A text's own codes (controls with arguments) are looked for first: the longest lead,
            // its whole length, decoded to nothing.
            val lead = codeLength(bytes, i, codes)
            if (lead > 0) {
                i += lead
                continue
            }
            val code = hex(bytes, i, unit)
            when {
                code in ends || controls[code] == "end" -> append("\n\n")
                code in newlines || controls[code] == "newline" -> append('\n')
                controls[code] == "page" || controls[code] == "clear" -> append("\n\n")
                code in controls -> Unit
                else -> append(chars[code] ?: "�")
            }
            i += unit
        }
    }

    /** [decode] for codes of up to 4 bytes, compared as ints: the same text, no string per code. */
    private fun decodePacked(bytes: ByteArray, unit: Int, controls: Map<String, String>, codes: List<Pair<ByteArray, Int>>): String {
        val table = packed(unit)
        val ctl = packedControls(controls, unit)
        val out = StringBuilder()
        var i = 0
        while (i + unit <= bytes.size) {
            val lead = codeLength(bytes, i, codes)
            if (lead > 0) {
                i += lead
                continue
            }
            val code = packedCode(bytes, i, unit)
            val control = ctl[code]
            when {
                code in table.ends || control == "end" -> out.append("\n\n")
                code in table.newlines || control == "newline" -> out.append('\n')
                control == "page" || control == "clear" -> out.append("\n\n")
                control != null -> Unit
                else -> out.append(table.chars[code] ?: "�")
            }
            i += unit
        }
        return out.toString()
    }

    companion object {
        private val LINE = Regex("^([0-9A-Fa-f]{2,8})=(.*)$")

        /** [unit] bytes at [at] as one int, in memory byte order (as the hex keys read). */
        fun packedCode(bytes: ByteArray, at: Int, unit: Int): Int {
            var v = 0
            for (k in 0 until unit) v = (v shl 8) or (bytes[at + k].toInt() and 0xFF)
            return v
        }

        /** Bytes in the code of [codes] (lead, whole length) starting at [i], the longest lead winning; 0 for none. */
        fun codeLength(bytes: ByteArray, i: Int, codes: List<Pair<ByteArray, Int>>): Int {
            var best = 0
            var bestLead = -1
            for (k in codes.indices) {
                val (lead, length) = codes[k]
                if (lead.size <= bestLead || i + lead.size > bytes.size) continue
                var match = true
                for (j in lead.indices) if (bytes[i + j] != lead[j]) { match = false; break }
                if (match) { best = length; bestLead = lead.size }
            }
            return best
        }

        fun parse(files: List<String>): CharTable {
            val chars = HashMap<String, String>()
            val ends = HashSet<String>()
            val newlines = HashSet<String>()
            for (text in files) for (raw in text.lines()) {
                val line = raw.removeSuffix("\r")
                if (line.isBlank() || line.startsWith("#") || line.startsWith(";")) continue
                if (line[0] == '/' || line[0] == '*') {
                    val key = line.substring(1).trim().substringBefore(' ').uppercase()
                    if (key.isEmpty() || key.length % 2 != 0) continue
                    (if (line[0] == '/') ends else newlines) += key
                    continue
                }
                val m = LINE.matchEntire(line) ?: continue
                if (m.groupValues[1].length % 2 != 0) continue
                chars[m.groupValues[1].uppercase()] = m.groupValues[2]
            }
            return CharTable(chars, ends, newlines)
        }

        private fun hex(bytes: ByteArray, at: Int, unit: Int): String =
            buildString { for (k in 0 until unit) append("%02X".format(bytes[at + k].toInt() and 0xFF)) }
    }
}
