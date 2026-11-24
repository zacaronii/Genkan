package app.genkan.anki

import app.genkan.dictionary.GlossaryEntry
import com.squareup.moshi.Moshi

/**
 * The short definitions a card shows, from the dictionaries' own definition JSON (ported from
 * Amatsubame's StructuredContentRenderer.glosses and CardFormatting.shortDefinition, so cards
 * mined in either app read the same).
 *
 * A sense is a list of glosses. Structured content (JMdict, Jitendex) gives its senses from its
 * glossary lists only, so example sentences, forms, cross-references and notes never come in. A
 * plain-text definition with numbered sense lines (新和英: `1 〔…〕 dark; deep.`) gives a sense per
 * line, its bracketed notes removed; other text is one sense with a gloss per line, its headword
 * line left out.
 */
object CardDefinitions {
    private val json = Moshi.Builder().build().adapter(Any::class.java).lenient()

    /** [glossary]'s senses, each its glosses; [headwords]: the entry's expression and reading. */
    fun glosses(glossary: String, headwords: Collection<String> = emptyList()): List<List<String>> {
        val parsed = try {
            json.fromJson(glossary)
        } catch (e: Exception) {
            glossary
        }
        val senses = mutableListOf<List<String>>()
        val strings = mutableListOf<String>()
        for (definition in parsed as? List<*> ?: listOf(parsed)) {
            when {
                definition is String -> strings += definition
                definition is Map<*, *> && definition["type"] == "text" -> strings += definition["text"] as? String ?: ""
                definition is Map<*, *> && definition["type"] == "structured-content" -> senses += glossaryLists(definition["content"])
            }
        }
        val text = strings.map { it.trim() }.filter { it.isNotEmpty() }
        return textSenses(text, headwords) + senses
    }

    /**
     * Plain-text definitions as senses: numbered lines (`1 …`), each without its 〔〕《》[]() notes
     * and split at semicolons, when the text has them; else one sense, a gloss per line (notes
     * removed), leaving out the headword line (`かな【漢字】…`, or the entry's own word).
     */
    private fun textSenses(text: List<String>, headwords: Collection<String>): List<List<String>> {
        val lines = text.flatMap { it.lines() }.map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val numbered = lines.mapNotNull { NUMBERED_LINE.matchEntire(it)?.groupValues?.get(1) }
        if (numbered.isEmpty()) {
            val glosses = lines.filterNot { isHeadword(it, headwords) }.map(::withoutTextNotes).filter { it.isNotEmpty() }
            return if (glosses.isEmpty()) emptyList() else listOf(glosses)
        }
        return numbered.map { line ->
            NOTES.replace(line, " ").split(';', '；')
                .map { it.trim().trimEnd('.', '。').trim() }
                .filter { it.isNotEmpty() }
        }.filter { it.isNotEmpty() }
    }

    /** A line naming the entry rather than defining it. */
    private fun isHeadword(line: String, headwords: Collection<String>): Boolean =
        HEADWORD_LINE.containsMatchIn(line) ||
            headwords.any { it.isNotBlank() && (line == it || line.startsWith("$it ") || line.startsWith("$it【")) }

    private fun withoutTextNotes(line: String) = NOTES.replace(line, " ").replace(SPACES, " ").trim().trimEnd('.', '。').trim()

    private fun glossaryLists(value: Any?): List<List<String>> = when (value) {
        is List<*> -> value.flatMap(::glossaryLists)
        is Map<*, *> -> if ((value["data"] as? Map<*, *>)?.get("content") == "glossary") {
            val items = value["content"] as? List<*> ?: listOf(value["content"])
            val glosses = items.map { plainText(it).trim() }.filter { it.isNotEmpty() }
            if (glosses.isEmpty()) emptyList() else listOf(glosses)
        } else {
            glossaryLists(value["content"])
        }
        else -> emptyList()
    }

    /** An element's text, without ruby readings or images. */
    private fun plainText(value: Any?): String = when (value) {
        is String -> value
        is List<*> -> value.joinToString("") { plainText(it) }
        is Map<*, *> -> if (value["tag"] in setOf("rt", "rp", "img")) "" else plainText(value["content"])
        else -> ""
    }

    /**
     * A glanceable definition: the first [maxSenses] senses, each with its first [maxGlosses]
     * glosses, one line per sense ("1. rich; strong; heavy"). Parenthetical notes are dropped
     * unless they're the whole gloss, and repeated glosses are skipped ("to run" repeats "run",
     * see [sameGloss]), as is a sense with nothing new.
     */
    fun shortDefinition(senses: List<List<String>>, maxSenses: Int = 3, maxGlosses: Int = 3): String {
        val seen = mutableSetOf<String>()
        val lines = senses.mapNotNull { sense ->
            val glosses = sense.map(::withoutNotes).filter { it.isNotEmpty() && seen.add(sameGloss(it)) }.take(maxGlosses).map(::capped)
            if (glosses.isEmpty()) null else glosses.joinToString("; ") { escape(it) }
        }.take(maxSenses)
        if (lines.size <= 1) return lines.firstOrNull().orEmpty()
        return lines.mapIndexed { i, line -> "${i + 1}. $line" }.joinToString("<br>")
    }

    /**
     * The senses a short definition is made from: the first dictionary, in the results' order,
     * whose entries give any glosses (a prose dictionary first doesn't leave it empty), leaving out
     * JMdict's forms entry (its spellings, not a meaning). When that dictionary has a single
     * sense, the other dictionaries' senses follow it, in the results' order (as Amatsubame does).
     */
    fun shortSenses(glossaries: List<GlossaryEntry>, headwords: Collection<String> = emptyList()): List<List<String>> {
        val meanings = glossaries.filter { "forms" !in it.definitionTags.split(' ') }
        val byDictionary = meanings.groupBy { it.dictionary }.mapValues { (_, entries) -> entries.flatMap { glosses(it.glossary, headwords) } }
        val order = meanings.map { it.dictionary }.distinct().filter { byDictionary[it].orEmpty().isNotEmpty() }
        val first = order.firstOrNull() ?: return emptyList()
        val senses = byDictionary.getValue(first)
        if (senses.size != 1) return senses
        return senses + order.drop(1).flatMap { byDictionary.getValue(it) }
    }

    /** A gloss as compared for repeats: without a leading "a", "an", "the" or "to", or trailing punctuation. */
    fun sameGloss(gloss: String): String =
        gloss.trim().replace(LEADING_ARTICLE, "").trimEnd { it.isWhitespace() || it in TRAILING_PUNCTUATION }

    /** SelectionText: the short definition of [glossaries]. */
    fun selectionText(glossaries: List<GlossaryEntry>, headwords: Collection<String> = emptyList()): String =
        shortDefinition(shortSenses(glossaries, headwords))

    /**
     * MainDefinition kept short: the first dictionary's entries, each as Yomitan writes it into a
     * card (its tags and dictionary, then the definition), with only its glosses: a list of them for
     * one sense, a numbered list of senses for several. Null when that dictionary gives no glosses
     * at all (a format this doesn't read): the card then takes the entries in full.
     */
    fun mainDefinition(glossaries: List<GlossaryEntry>, headwords: Collection<String> = emptyList()): String? {
        val first = glossaries.firstOrNull()?.dictionary ?: return null
        val items = glossaries.filter { it.dictionary == first && "forms" !in it.definitionTags.split(' ') }.mapNotNull { entry ->
            val senses = glosses(entry.glossary, headwords)
            if (senses.isEmpty()) return@mapNotNull null
            val label = (entry.definitionTags.split(' ').filter { it.isNotEmpty() } + entry.dictionary).joinToString(", ") { escape(it) }
            val body = if (senses.size == 1) {
                senses.single().joinToString("", "<ul>", "</ul>") { "<li>${escape(it)}</li>" }
            } else {
                senses.joinToString("", "<ol>", "</ol>") { sense -> "<li>${sense.joinToString("; ") { escape(it) }}</li>" }
            }
            "<li data-dictionary=\"${escape(entry.dictionary)}\"><i>($label)</i> $body</li>"
        }
        if (items.isEmpty()) return null
        return items.joinToString("", "<div style=\"text-align: left;\" class=\"yomitan-glossary\"><ol>", "</ol></div>")
    }

    private fun withoutNotes(gloss: String): String {
        val stripped = PARENTHESES.replace(gloss, " ").trim()
        return stripped.ifEmpty { gloss.trim() }
    }

    /** A gloss at most [MAX_GLOSS] characters, cut with "…". */
    private fun capped(gloss: String) = if (gloss.length <= MAX_GLOSS) gloss else gloss.take(MAX_GLOSS - 1).trimEnd() + "…"

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val MAX_GLOSS = 80

    /** A headword line, `かな【漢字】…`: kana, then the written forms in 【】, at its start. */
    private val HEADWORD_LINE = Regex("""^\S{0,20}?【[^】]*】""")

    private val SPACES = Regex("""\s+""")

    private val LEADING_ARTICLE = Regex("""^(?:a|an|the|to)\s+""")

    private const val TRAILING_PUNCTUATION = ".,;:!?。、！？"

    private val PARENTHESES = Regex("""\s*[(（][^()（）]*[)）]\s*""")

    /** A numbered sense line: its number, then its text. */
    private val NUMBERED_LINE = Regex("""^\d+\s+(.+)$""")

    /** 新和英's notes: 〔usage〕, 《field》, [label], (aside). */
    private val NOTES = Regex("""〔[^〕]*〕|《[^》]*》|\[[^\]]*]|［[^］]*］|[(（][^()（）]*[)）]""")
}
