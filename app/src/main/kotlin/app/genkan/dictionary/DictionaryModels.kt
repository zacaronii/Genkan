package app.genkan.dictionary

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** What hoshidicts reports after importing one Yomitan zip. */
@JsonClass(generateAdapter = true)
data class ImportReport(
    val success: Boolean,
    val title: String = "",
    val error: String = "",
    val terms: Long = 0,
    val meta: Long = 0,
    val frequencies: Long = 0,
    val pitches: Long = 0,
    val kanji: Long = 0,
    val media: Long = 0,
)

@JsonClass(generateAdapter = true)
data class TransformStep(val name: String, val description: String = "")

@JsonClass(generateAdapter = true)
data class GlossaryEntry(
    val dictionary: String,
    /** The dictionary's own definition JSON (structured content or plain strings). */
    val glossary: String,
    val definitionTags: String = "",
    val termTags: String = "",
)

@JsonClass(generateAdapter = true)
data class FrequencyValue(val value: Int, val display: String = "")

@JsonClass(generateAdapter = true)
data class FrequencyEntry(val dictionary: String, val values: List<FrequencyValue> = emptyList())

@JsonClass(generateAdapter = true)
data class Pitch(
    val position: Int,
    val pattern: String = "",
    val nasal: List<Int> = emptyList(),
    val devoice: List<Int> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class PitchEntry(
    val dictionary: String,
    val pitches: List<Pitch> = emptyList(),
    val transcriptions: List<String> = emptyList(),
)

/** One headword found for the start of the looked-up text. */
@JsonClass(generateAdapter = true)
data class TermHit(
    /** The text as it appeared (眺めた). */
    val matched: String,
    /** The form looked up after undoing conjugation (眺める). */
    val deinflected: String,
    val trace: List<TransformStep> = emptyList(),
    val expression: String,
    val reading: String = "",
    val rules: String = "",
    val score: Double = 0.0,
    val glossaries: List<GlossaryEntry> = emptyList(),
    val frequencies: List<FrequencyEntry> = emptyList(),
    val pitches: List<PitchEntry> = emptyList(),
)

/** A word found in a line for marking: where it is (UTF-16), and its dictionary headword and reading. */
@JsonClass(generateAdapter = true)
data class ScannedWord(
    @Json(name = "s") val start: Int,
    @Json(name = "l") val length: Int,
    @Json(name = "e") val expression: String,
    @Json(name = "r") val reading: String = "",
) {
    val end: Int get() = start + length
}

/** A lookup's hits, plus the JSON they came from for the popup page. */
data class LookupResult(val text: String, val hits: List<TermHit>, val json: String) {
    /** How many characters (UTF-16 units) of the text the longest hit covers. */
    val matchedLength: Int get() = hits.maxOfOrNull { it.matched.length } ?: 0
}

@JsonClass(generateAdapter = true)
data class KanjiStat(val key: String, val value: String)

@JsonClass(generateAdapter = true)
data class KanjiEntry(
    val dictionary: String,
    val onyomi: String = "",
    val kunyomi: String = "",
    val tags: String = "",
    val definitions: List<String> = emptyList(),
    val stats: List<KanjiStat> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DictionaryStyle(val dictionary: String, val css: String)
