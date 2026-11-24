package app.genkan.anki

import app.genkan.dictionary.TermHit

/**
 * What a card is made from: the looked-up entry, the line it was found in, and what the popup
 * rendered (the glossary as HTML, the furigana as `漢字[かんじ]`).
 */
data class MineSource(
    val hit: TermHit,
    /** The backlog line, and where the looked-up word sits in it. */
    val sentence: String,
    val matchStart: Int,
    val matchLength: Int,
    val glossaryHtml: String,
    val firstGlossaryHtml: String,
    val furiganaPlain: String,
    val gameTitle: String,
    val pictureFile: String? = null,
    val sentenceAudioFile: String? = null,
)

/**
 * Fills a Lapis or Kiku note the way Yomitan's handlebars do for those note types (Hoshi
 * Reader's AnkiFieldTemplates is the mapping): Expression, ExpressionFurigana, Sentence with the
 * word in bold, Glossary, pitch, Frequency and FreqSort. Unknown note types get the same names,
 * and fields the note type lacks are dropped by [forNoteType].
 */
object CardFields {

    fun lapis(source: MineSource): Map<String, String> {
        val hit = source.hit
        return buildMap {
            put("Expression", hit.expression)
            put("ExpressionFurigana", source.furiganaPlain)
            put("ExpressionReading", hit.reading.ifEmpty { hit.expression })
            // A short definition Lapis shows in MainDefinition's place, and MainDefinition with
            // only the first dictionary's glosses; Glossary keeps every entry in full.
            val headwords = listOf(hit.expression, hit.reading)
            put("SelectionText", CardDefinitions.selectionText(hit.glossaries, headwords))
            put("MainDefinition", CardDefinitions.mainDefinition(hit.glossaries, headwords) ?: source.firstGlossaryHtml)
            put("Sentence", sentenceWithWord(source.sentence, source.matchStart, source.matchLength))
            put("Glossary", source.glossaryHtml)
            put("PitchPosition", pitchPositions(hit))
            put("Frequency", frequencies(hit))
            put("FreqSort", harmonicRank(hit).toString())
            put("MiscInfo", source.gameTitle)
            source.pictureFile?.let { put("Picture", "<img src=\"$it\">") }
            source.sentenceAudioFile?.let { put("SentenceAudio", "[sound:$it]") }
        }
    }

    /** Only the fields [noteFields] has, so a note type without one still takes the card. */
    fun forNoteType(fields: Map<String, String>, noteFields: List<String>?): Map<String, String> =
        if (noteFields == null) fields else fields.filterKeys { it in noteFields }

    /** The sentence with the looked-up word in bold, as Lapis shows it. */
    fun sentenceWithWord(sentence: String, start: Int, length: Int): String {
        val s = start.coerceIn(0, sentence.length)
        val e = (start + length).coerceIn(s, sentence.length)
        return escape(sentence.substring(0, s)) + "<b>" + escape(sentence.substring(s, e)) + "</b>" + escape(sentence.substring(e))
    }

    /** Yomitan's {pitch-accent-positions}: one number, or a list when there are several. */
    fun pitchPositions(hit: TermHit): String {
        val positions = hit.pitches.flatMap { it.pitches }.map { it.position }.filter { it >= 0 }.distinct()
        return when (positions.size) {
            0 -> ""
            1 -> positions.single().toString()
            else -> positions.joinToString("", "<ol>", "</ol>") { "<li>$it</li>" }
        }
    }

    /** Yomitan's {frequencies}: each frequency dictionary's values. */
    fun frequencies(hit: TermHit): String {
        val items = hit.frequencies.filter { it.values.isNotEmpty() }.map { f ->
            "<li>${escape(f.dictionary)}: ${f.values.joinToString(", ") { escape(it.display.ifEmpty { it.value.toString() }) }}</li>"
        }
        return if (items.isEmpty()) "" else items.joinToString("", "<ul style=\"text-align: left;\">", "</ul>")
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

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    const val NO_RANK = 9_999_999
}
