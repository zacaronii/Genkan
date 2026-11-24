package app.genkan.anki

import app.genkan.dictionary.FrequencyEntry
import app.genkan.dictionary.FrequencyValue
import app.genkan.dictionary.Pitch
import app.genkan.dictionary.PitchEntry
import app.genkan.dictionary.TermHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CardFieldsTest {

    private val hit = TermHit(
        matched = "眺めた",
        deinflected = "眺める",
        expression = "眺める",
        reading = "ながめる",
        frequencies = listOf(FrequencyEntry("JPDB", listOf(FrequencyValue(926, "926"), FrequencyValue(19047, "19047㋕")))),
        pitches = listOf(PitchEntry("アクセント辞典", listOf(Pitch(3)))),
    )

    private val source = MineSource(
        hit = hit,
        sentence = "彼女はそう言って、窓の外を眺めた。",
        matchStart = 13,
        matchLength = 3,
        glossaryHtml = "<ol><li>to look at</li></ol>",
        firstGlossaryHtml = "<ol><li>to look at</li></ol>",
        furiganaPlain = "眺[なが]める",
        gameTitle = "マルコと銀河竜",
        pictureFile = "genkan_1.webp",
    )

    @Test
    fun `lapis fields come from the entry, the line and the game`() {
        val fields = CardFields.lapis(source)
        assertEquals("眺める", fields["Expression"])
        assertEquals("眺[なが]める", fields["ExpressionFurigana"])
        assertEquals("ながめる", fields["ExpressionReading"])
        assertEquals("彼女はそう言って、窓の外を<b>眺めた</b>。", fields["Sentence"])
        assertEquals(CardFields.downstepNotation(3), fields["PitchPosition"])
        assertEquals("x", fields["IsWordAndSentenceCard"])
        assertEquals("926", fields["FreqSort"])
        assertEquals("<img src=\"genkan_1.webp\">", fields["Picture"])
        assertEquals("マルコと銀河竜", fields["MiscInfo"])
    }

    @Test
    fun `a card without a picture leaves the field out`() {
        assertFalse("Picture" in CardFields.lapis(source.copy(pictureFile = null)))
    }

    @Test
    fun `fields the note type lacks are dropped`() {
        val fields = CardFields.forNoteType(CardFields.lapis(source), listOf("Expression", "Sentence"))
        assertEquals(setOf("Expression", "Sentence"), fields.keys)
    }

    @Test
    fun `the sentence is escaped around the bold word`() {
        assertEquals("a&lt;b <b>x</b> &amp;", CardFields.sentenceWithWord("a<b x &", 4, 1))
        assertEquals("ab<b></b>", CardFields.sentenceWithWord("ab", 9, 3))
    }

    @Test
    fun `harmonic rank takes each dictionary's best rank`() {
        val two = hit.copy(frequencies = hit.frequencies + FrequencyEntry("Other", listOf(FrequencyValue(2000))))
        // 2 / (1/926 + 1/2000) = 1265.8
        assertEquals(1265, CardFields.harmonicRank(two))
        assertEquals(CardFields.NO_RANK, CardFields.harmonicRank(hit.copy(frequencies = emptyList())))
    }

    @Test
    fun `several pitch positions become a list`() {
        val two = hit.copy(pitches = listOf(PitchEntry("p", listOf(Pitch(0), Pitch(2)))))
        assertEquals("<ol><li>${CardFields.downstepNotation(0)}</li><li>${CardFields.downstepNotation(2)}</li></ol>", CardFields.pitchPositions(two))
    }

    @Test
    fun `a pitch position is Yomitan's downstep notation`() {
        assertEquals(
            "<span class=\"pronunciation-downstep-notation\" data-downstep-position=\"0\" style=\"display: inline;\">" +
                "<span class=\"pronunciation-downstep-notation-prefix\">[</span>" +
                "<span class=\"pronunciation-downstep-notation-number\">0</span>" +
                "<span class=\"pronunciation-downstep-notation-suffix\">]</span></span>",
            CardFields.downstepNotation(0),
        )
        assertEquals("", CardFields.pitchPositions(hit.copy(pitches = emptyList())))
    }

    @Test
    fun `pitch categories follow Yomitan's`() {
        fun categories(reading: String, rules: String, vararg positions: Int) =
            CardFields.pitchCategories(hit.copy(reading = reading, rules = rules, pitches = listOf(PitchEntry("p", positions.map { Pitch(it) }))))
        // A noun: flat, first mora, middle, last.
        assertEquals("heiban", categories("ぞくせい", "", 0))
        assertEquals("atamadaka", categories("ねこ", "", 1))
        assertEquals("nakadaka", categories("あおぞら", "", 3))
        assertEquals("odaka", categories("いもうと", "", 4))
        // A verb's or an i-adjective's downstep is kifuku.
        assertEquals("kifuku", categories("ながめる", "v1", 3))
        assertEquals("heiban,kifuku", categories("あかい", "adj-i", 0, 2))
        // Small kana join the mora before them: きょう is two morae.
        assertEquals("odaka", categories("きょう", "", 2))
        assertEquals("", CardFields.pitchCategories(hit.copy(pitches = emptyList())))
    }

    @Test
    fun `bare hosts get http and AnkiConnect's port, full URLs are kept`() {
        assertEquals("http://192.0.2.10:8765", AnkiConfig(address = "192.0.2.10").url)
        assertEquals("http://anki.local:9000", AnkiConfig(address = "anki.local:9000").url)
        // Behind a reverse proxy the scheme's own port is the right one.
        assertEquals("https://anki.example.com", AnkiConfig(address = "https://anki.example.com/").url)
        assertEquals("http://192.0.2.10:8765", AnkiConfig(address = "http://192.0.2.10:8765").url)
    }
}
