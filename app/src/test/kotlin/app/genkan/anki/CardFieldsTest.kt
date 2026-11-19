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
        assertEquals("3", fields["PitchPosition"])
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
        assertEquals("<ol><li>0</li><li>2</li></ol>", CardFields.pitchPositions(two))
    }

    @Test
    fun `addresses get a scheme and AnkiConnect's port`() {
        assertEquals("http://192.0.2.10:8765", AnkiConfig(address = "192.0.2.10").url)
        assertEquals("http://anki.local:9000", AnkiConfig(address = "anki.local:9000").url)
        assertEquals("https://anki.example.com:8765", AnkiConfig(address = "https://anki.example.com/").url)
    }
}
