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
    fun `several pitch positions become a list, from the first pitch dictionary only`() {
        val two = hit.copy(pitches = listOf(PitchEntry("p", listOf(Pitch(0), Pitch(2))), PitchEntry("q", listOf(Pitch(4)))))
        val fields = CardFields.lapis(source.copy(hit = two))
        assertEquals("<ol><li>${CardFields.downstepNotation(0)}</li><li>${CardFields.downstepNotation(2)}</li></ol>", fields["PitchPosition"])
        // No verb rule on this hit: ながめる's drop at 2 of 4 morae is nakadaka.
        assertEquals("heiban,nakadaka", fields["PitchCategories"])
    }

    @Test
    fun `a pitch position is Yomitan's downstep notation, as Amatsubame writes it`() {
        assertEquals(
            "<span class=\"pronunciation-downstep-notation\" data-downstep-position=\"0\" style=\"display:inline;\">" +
                "<span class=\"pronunciation-downstep-notation-prefix\">[</span>" +
                "<span class=\"pronunciation-downstep-notation-number\">0</span>" +
                "<span class=\"pronunciation-downstep-notation-suffix\">]</span></span>",
            CardFields.downstepNotation(0),
        )
        assertEquals("", CardFields.pitchPosition(emptyList()))
    }

    @Test
    fun `pitch categories follow Amatsubame's`() {
        fun categories(expression: String, reading: String, rules: String, vararg downsteps: Int) =
            CardFields.pitchCategories(downsteps.toList(), expression, reading, rules.split(' ').filter(String::isNotEmpty))
        // A noun: flat, first mora, the last, else the middle.
        assertEquals("heiban", categories("語", "ぞくせい", "", 0))
        assertEquals("atamadaka", categories("語", "ねこ", "", 1))
        assertEquals("nakadaka", categories("語", "あおぞら", "", 3))
        assertEquals("odaka", categories("語", "いもうと", "", 4))
        // A verb's or an i-adjective's drop is kifuku.
        assertEquals("kifuku", categories("眺める", "ながめる", "v1", 3))
        assertEquals("heiban,kifuku", categories("赤い", "あかい", "adj-i", 0, 2))
        // A する-noun is a noun; the する verb is a verb.
        assertEquals("nakadaka", categories("勉強", "べんきょう", "vs", 2))
        assertEquals("kifuku", categories("勉強する", "べんきょうする", "vs", 2))
        // Small kana join the mora before them: きょう is two morae.
        assertEquals("odaka", categories("語", "きょう", "", 2))
        assertEquals("", categories("語", "ねこ", ""))
    }

    @Test
    fun `frequencies are an item per value, as Amatsubame writes them`() {
        assertEquals(
            "<ul data-content=\"frequencies\"><li data-dictionary=\"JPDB\">JPDB: 926</li><li data-dictionary=\"JPDB\">JPDB: 19047㋕</li></ul>",
            CardFields.frequencies(hit),
        )
        assertEquals("", CardFields.frequencies(hit.copy(frequencies = emptyList())))
    }

    @Test
    fun `MiscInfo is the game and its platform`() {
        assertEquals("マルコと銀河竜 — Nintendo GameCube", CardFields.lapis(source.copy(platformName = "Nintendo GameCube"))["MiscInfo"])
        assertEquals("A &amp; B", CardFields.miscInfo("A & B", null))
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
