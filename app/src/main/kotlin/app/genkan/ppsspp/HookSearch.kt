package app.genkan.ppsspp

import app.genkan.yomikata.CatalogEntry
import app.genkan.yomikata.CleanupStep
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset

/**
 * The parts of the hook finder (Yomikata's docs/hook-finder.md) that need no emulator: comparing
 * a candidate's text with what OCR read, keeping the candidates that hold line after line, the
 * cleanup a hook's text needs, and the report.
 */
object HookSearch {

    /** The charsets a PSP line is looked for in, by their catalog names. */
    val CHARSETS: List<Pair<String, Charset>> = listOf(
        "utf16le" to Charsets.UTF_16LE,
        "shift_jis" to PspHooks.SHIFT_JIS,
        "utf8" to Charsets.UTF_8,
    )

    private val TAG = Regex("<[^<>\n]{1,40}>")
    private val TAG_NAME = Regex("<([A-Za-z]+)")

    /** Text as compared: layout (spaces, line breaks, byte-order marks) left out. */
    fun normalise(text: String): String = text.filterNot { it.isWhitespace() || it == '\uFEFF' || it == '\u3000' }

    /** How alike two lines are, 0 to 1: one minus their edit distance over the longer's length. */
    fun similarity(a: String, b: String): Double {
        val x = normalise(a)
        val y = normalise(b)
        if (x.isEmpty() && y.isEmpty()) return 1.0
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var previous = IntArray(y.length + 1) { it }
        var current = IntArray(y.length + 1)
        for (i in 1..x.length) {
            current[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current.also { current = previous }
        }
        return 1.0 - previous[y.length].toDouble() / maxOf(x.length, y.length)
    }

    /**
     * How well a hook's line fits inside what OCR read, 0 to 1: OCR's box may also hold a
     * speaker's name or a prompt, so text before and after the line costs nothing, while the
     * line's own misreads and anything it has that OCR doesn't do.
     */
    fun fit(hook: String, ocr: String): Double {
        val x = normalise(hook)
        val y = normalise(ocr)
        if (x.isEmpty()) return if (y.isEmpty()) 1.0 else 0.0
        // Edit distance with free skipping of y's start (row 0 all zero) and end (min of the last row).
        var previous = IntArray(y.length + 1)
        var current = IntArray(y.length + 1)
        for (i in 1..x.length) {
            current[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current.also { current = previous }
        }
        return (1.0 - previous.min().toDouble() / x.length).coerceAtLeast(0.0)
    }

    /** A candidate's line, before its cleanup is known: tags out, so only the words are compared. */
    fun rough(raw: String): String = raw.replace(TAG, "")

    /**
     * Pieces of an OCR line to look for in memory: a few runs of [length] characters from its
     * start, middle and end, since OCR may have misread any one of them.
     */
    fun probes(ocr: String, length: Int = 5): List<String> {
        val text = normalise(ocr)
        if (text.length <= length) return listOfNotNull(text.takeIf { it.length >= 3 })
        return listOf(0, (text.length - length) / 2, text.length - length).distinct().map { text.substring(it, it + length) }
    }

    /** Where [needle] occurs in [haystack], at most [limit] places. */
    fun indexesOf(haystack: ByteArray, needle: ByteArray, limit: Int = 32): List<Int> {
        if (needle.isEmpty()) return emptyList()
        val found = mutableListOf<Int>()
        val first = needle[0]
        var i = 0
        outer@ while (i <= haystack.size - needle.size && found.size < limit) {
            if (haystack[i] == first) {
                for (k in 1 until needle.size) if (haystack[i + k] != needle[k]) { i++; continue@outer }
                found += i
            }
            i++
        }
        return found
    }

    /**
     * The cleanup that makes a hook's [raws] read like the screen ([ocrs], the same lines): tags
     * the game puts in its text (`<CLT 4>`) and line breaks OCR doesn't see. Rules use the
     * catalog's portable regex subset.
     */
    fun cleanupFor(raws: List<String>, ocrs: List<String>): JSONArray {
        val rules = JSONArray()
        val shown = ocrs.joinToString("")
        val names = raws.flatMap { raw -> TAG.findAll(raw).mapNotNull { TAG_NAME.find(it.value)?.groupValues?.get(1) }.toList() }
            .distinct().filter { "<$it" !in shown }.sorted()
        if (names.isNotEmpty()) {
            val alternatives = if (names.size == 1) names[0] else names.joinToString("|", "(?:", ")")
            rules.put(JSONObject().put("regex", "<$alternatives[^<>\\n]*>").put("replace", ""))
        }
        if (raws.any { '\uFEFF' in it }) rules.put(JSONObject().put("regex", "\uFEFF").put("replace", ""))
        if (raws.any { '\n' in it.trim() }) rules.put(JSONObject().put("regex", "\\n").put("replace", ""))
        if (raws.any { it != it.trim() }) rules.put(JSONObject().put("regex", "^\\s+|\\s+$").put("replace", ""))
        return rules
    }

    /** [raw] after [rules], as a reader using the hook would show it (null: a dropped line). */
    fun clean(raw: String, rules: JSONArray): String? {
        var text = raw
        for (step in CleanupStep.parse(rules)) text = step.apply(text)
        return text.takeIf { it.isNotBlank() }
    }

    /** One hit of a candidate's breakpoint: the text each register pointed at (in user RAM). */
    data class Hit(val texts: Map<String, String>)

    /**
     * Candidates (a function entry and a register) followed over the lines of play: one holds
     * when its entry fired at most [maxHitsPerLine] times for a line and the register's text was
     * the new line. An entry that fires more than that for a line reads everything (copies,
     * glyph renderers) and is dropped for good. The first to hold for [needed] lines running wins.
     */
    class Tracker(private val needed: Int = 3, private val maxHitsPerLine: Int = 2, private val threshold: Double = 0.7) {
        data class Candidate(val entry: Long, val register: String)

        private val streaks = HashMap<Candidate, Int>()
        val rejected = HashSet<Long>()

        /** One line OCR read, and each entry's hits since the line before; the winner once there is one. */
        fun line(ocr: String, hits: Map<Long, List<Hit>>): Candidate? {
            val holding = HashSet<Candidate>()
            for ((entry, list) in hits) {
                if (entry in rejected) continue
                if (list.size > maxHitsPerLine) {
                    rejected += entry
                    continue
                }
                for (hit in list) for ((register, text) in hit.texts) {
                    if (fit(rough(text), ocr) >= threshold) holding += Candidate(entry, register)
                }
            }
            streaks.keys.retainAll(holding)
            for (c in holding) streaks[c] = (streaks[c] ?: 0) + 1
            return streaks.entries.filter { it.value >= needed }.maxByOrNull { it.value }?.key
        }

        fun streak(candidate: Candidate): Int = streaks[candidate] ?: 0
        val best: Int get() = streaks.values.maxOrNull() ?: 0
    }

    /** A checked line: what OCR read, and what the hook gave after cleanup. */
    data class Checked(val ocr: String, val hook: String) {
        fun matches(threshold: Double = MATCH): Boolean = fit(hook, ocr) >= threshold
    }

    /** The report for an inbox (`POST /api/hooks`), as docs/hook-finder.md describes it. */
    fun report(
        id: String,
        title: String,
        discVersion: String,
        hook: CatalogEntry.Hook,
        charset: String,
        rules: JSONArray,
        pairs: List<Checked>,
        app: String,
        appVersion: String,
        emulatorVersion: String,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("platform", "psp")
        .put("disc_version", discVersion)
        .put("title", title)
        .put("exe_sha1", "")
        .put(
            "hook",
            JSONObject().put("address", "0x%08X".format(hook.address)).put("register", hook.register).put("charset", charset).put("cleanup", rules),
        )
        .put(
            "evidence",
            JSONObject()
                .put("lines_checked", pairs.size)
                .put("lines_matched", pairs.count { it.matches() })
                .put("pairs", JSONArray(pairs.take(MAX_PAIRS).map { JSONObject().put("ocr", it.ocr.take(MAX_PAIR_TEXT)).put("hook", it.hook.take(MAX_PAIR_TEXT)) })),
        )
        .put("reporter", JSONObject().put("app", app).put("version", appVersion))
        .put("emulator", JSONObject().put("name", "PPSSPP").put("version", emulatorVersion))
        .put("notes", "")

    /** A MIPS `addiu sp, sp, -n`: the usual first instruction of a function. */
    fun isFrameSetup(word: Int): Boolean = (word ushr 16) == 0x27BD && (word and 0x8000) != 0

    /** How close an OCR line and a hook's must be to count as the same line. */
    const val MATCH = 0.8
    const val MAX_PAIRS = 20
    const val MAX_PAIR_TEXT = 300
}
