package app.genkan.anki

import app.genkan.dictionary.TermHit

/**
 * What a card is made from: the looked-up entry, the line it was found in, the game, and the
 * furigana as the popup splits it (`漢字[かんじ]`).
 */
data class MineSource(
    val hit: TermHit,
    /** The backlog line, and where the looked-up word sits in it. */
    val sentence: String,
    val matchStart: Int,
    val matchLength: Int,
    val furiganaPlain: String,
    val gameTitle: String,
    /** The game's platform, as RomM names it, for MiscInfo; null when there's none. */
    val platformName: String? = null,
    val pictureFile: String? = null,
    val sentenceAudioFile: String? = null,
)

/**
 * Fills a Lapis or Kiku note as Amatsubame does (its MiningService and CardFormatting), which
 * writes Yomitan's formats, so every app's cards are the same: Expression, ExpressionFurigana,
 * the short definition, the first dictionary in full, every dictionary, the sentence with the
 * word in bold, pitch, Frequency and FreqSort. Unknown note types get the same names, and fields
 * the note type lacks are dropped by [forNoteType]. ExpressionAudio is added as the card is sent
 * ([WordAudio]).
 */
object CardFields {

    fun lapis(source: MineSource): Map<String, String> {
        val hit = source.hit
        val reading = hit.reading.ifEmpty { hit.expression }
        val items = hit.glossaries.map { GlossaryItem(it.dictionary, it.definitionTags.split(' ').filter(String::isNotEmpty), CardHtml.renderOrNull(it.glossary)) }
        val firstDictionary = items.firstOrNull()?.dictionary
        val firstItems = items.filter { it.dictionary == firstDictionary }
        val headwords = listOf(hit.expression, hit.reading)
        val downsteps = hit.pitches.firstOrNull { p -> p.pitches.any { it.position >= 0 } }?.pitches?.map { it.position }?.filter { it >= 0 }.orEmpty()
        return buildMap {
            put("Expression", hit.expression)
            put("ExpressionFurigana", source.furiganaPlain)
            put("ExpressionReading", reading)
            // The short definition Lapis shows; MainDefinition is the first dictionary in full
            // (its glosses alone only when it renders nothing), Glossary every dictionary.
            put("SelectionText", CardDefinitions.selectionText(hit.glossaries, headwords))
            put(
                "MainDefinition",
                if (firstItems.any { it.html != null }) glossary(firstItems) else CardDefinitions.mainDefinition(hit.glossaries, headwords) ?: glossary(firstItems),
            )
            put("Sentence", sentenceWithWord(source.sentence, source.matchStart, source.matchLength))
            put("Glossary", glossary(items))
            put("PitchPosition", pitchPosition(downsteps))
            put("PitchCategories", pitchCategories(downsteps, hit.expression, reading, hit.rules.split(' ').filter(String::isNotEmpty)))
            put("Frequency", frequencies(hit))
            put("FreqSort", harmonicRank(hit).toString())
            put("MiscInfo", miscInfo(source.gameTitle, source.platformName))
            // Lapis shows a word-and-sentence card: the word, then its sentence, as cards made elsewhere do.
            put("IsWordAndSentenceCard", "x")
            source.pictureFile?.let { put("Picture", "<img src=\"$it\">") }
            source.sentenceAudioFile?.let { put("SentenceAudio", "[sound:$it]") }
        }
    }

    /** One definition for the glossary: its dictionary, tags (the sense number first) and HTML (null: it renders nothing). */
    data class GlossaryItem(val dictionary: String, val tags: List<String>, val html: String?)

    /** Yomitan's glossary markup: a list item per definition, labelled with its tags and dictionary. */
    fun glossary(items: List<GlossaryItem>): String {
        if (items.isEmpty()) return ""
        val list = items.joinToString("") { item ->
            val label = (item.tags + item.dictionary).joinToString(", ") { CardHtml.escape(it) }
            "<li data-dictionary=\"${CardHtml.escape(item.dictionary)}\"><i>($label)</i> ${item.html ?: CardHtml.EMPTY}</li>"
        }
        return "<div style=\"text-align: left;\" class=\"yomitan-glossary\"><ol>$list</ol></div>"
    }

    /** `Game — Platform`, the game alone when the platform isn't known. */
    fun miscInfo(title: String, platform: String?): String =
        CardHtml.escape(if (platform.isNullOrBlank()) title else "$title — $platform")

    /** Only the fields [noteFields] has, so a note type without one still takes the card. */
    fun forNoteType(fields: Map<String, String>, noteFields: List<String>?): Map<String, String> =
        if (noteFields == null) fields else fields.filterKeys { it in noteFields }

    /** The sentence with the looked-up word in bold, as Lapis shows it. */
    fun sentenceWithWord(sentence: String, start: Int, length: Int): String {
        val s = start.coerceIn(0, sentence.length)
        val e = (start + length).coerceIn(s, sentence.length)
        return CardHtml.escape(sentence.substring(0, s)) + "<b>" + CardHtml.escape(sentence.substring(s, e)) + "</b>" + CardHtml.escape(sentence.substring(e))
    }

    /** Yomitan's downstep notation for each of [downsteps], `[0]`; a list when there are several. */
    fun pitchPosition(downsteps: List<Int>): String {
        val spans = downsteps.map(::downstepNotation)
        if (spans.size <= 1) return spans.firstOrNull().orEmpty()
        return spans.joinToString("", "<ol>", "</ol>") { "<li>$it</li>" }
    }

    fun downstepNotation(position: Int): String =
        "<span class=\"pronunciation-downstep-notation\" data-downstep-position=\"$position\" style=\"display:inline;\">" +
            "<span class=\"pronunciation-downstep-notation-prefix\">[</span>" +
            "<span class=\"pronunciation-downstep-notation-number\">$position</span>" +
            "<span class=\"pronunciation-downstep-notation-suffix\">]</span></span>"

    /**
     * Yomitan's pitch categories, comma-separated: heiban, atamadaka, nakadaka, odaka, and kifuku
     * for a verb's or i-adjective's drop. A する-noun (rule vs) is a noun; only a headword
     * ending in する counts as the verb.
     */
    fun pitchCategories(downsteps: List<Int>, expression: String, reading: String, rules: List<String>): String {
        val morae = moraCount(reading)
        val inflecting = rules.any { rule -> if (rule == "vs") expression.endsWith("する") else rule.startsWith("v") || rule == "adj-i" }
        return downsteps.map { downstep ->
            when {
                downstep == 0 -> "heiban"
                inflecting -> "kifuku"
                downstep == 1 -> "atamadaka"
                downstep == morae -> "odaka"
                else -> "nakadaka"
            }
        }.distinct().joinToString(",")
    }

    /** Morae in a reading: a small kana (ゃ, ァ) joins the one before it. */
    fun moraCount(reading: String): Int {
        var count = 0
        reading.forEachIndexed { i, c -> if (c !in SMALL_KANA || i == 0) count++ }
        return count
    }

    private const val SMALL_KANA = "ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ"

    /** Each frequency dictionary's values, an item each, as Amatsubame writes them. */
    fun frequencies(hit: TermHit): String {
        val items = hit.frequencies.flatMap { f ->
            f.values.map { v ->
                val name = CardHtml.escape(f.dictionary)
                "<li data-dictionary=\"$name\">$name: ${CardHtml.escape(v.display.ifEmpty { v.value.toString() })}</li>"
            }
        }
        return if (items.isEmpty()) "" else items.joinToString("", "<ul data-content=\"frequencies\">", "</ul>")
    }

    /**
     * Yomitan's {frequency-harmonic-rank}: the harmonic mean of each frequency dictionary's
     * best (lowest) rank, 9999999 when no dictionary has the word. Lapis sorts by it.
     */
    fun harmonicRank(hit: TermHit): Int {
        val ranks = hit.frequencies.mapNotNull { f -> f.values.map { it.value }.filter { it > 0 }.minOrNull() }
        if (ranks.isEmpty()) return NO_RANK
        return (ranks.size / ranks.sumOf { 1.0 / it }).toInt()
    }


    const val NO_RANK = 9_999_999
}
