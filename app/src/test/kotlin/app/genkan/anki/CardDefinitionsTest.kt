package app.genkan.anki

import app.genkan.dictionary.GlossaryEntry
import app.genkan.dictionary.TermHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up entries in the dictionaries' own shapes; the first four tests are Amatsubame's. */
class CardDefinitionsTest {

    /** One sense as JMdict and Jitendex write it: a glossary list, then whatever else the sense has. */
    private fun structured(glosses: List<String>, extra: String = "") =
        """[{"type":"structured-content","content":[{"tag":"ul","data":{"content":"glossary"},"content":[""" +
            glosses.joinToString(",") { """{"tag":"li","content":"$it"}""" } + "]}" + extra + "]}]"

    private val examples = """,{"tag":"ul","data":{"content":"examples"},"content":[{"tag":"li","content":"例文です。"},{"tag":"li","content":"An example."}]}"""
    private val jitendexNoise = """,{"tag":"div","data":{"content":"extra-info"},"content":"a long note"},""" +
        """{"tag":"div","data":{"content":"forms"},"content":{"tag":"table","content":"spellings"}},""" +
        """{"tag":"div","data":{"content":"xref"},"content":"see also"}"""

    /** A made-up text dictionary entry in 新和英's layout. */
    private val numberedText = """["ためし【試し】 [ローマ字](tameshi)\n1 〔使い方〕 trial; test; attempt.\n2 《分野》 sample [label]; specimen (aside).\n"]"""

    @Test
    fun `the short definition is the first glosses of the first senses`() {
        val senses = listOf(
            listOf("rich (flavor, color, etc.)", "strong (e.g. odor)", "heavy", "thick (soup, makeup, etc.)", "dense"),
            listOf("probable", "(very) likely", "strong (suspicion, sense, etc.)"),
            listOf("passionate", "sensuous", "hot"),
            listOf("unused fourth sense"),
        )
        assertEquals("1. rich; strong; heavy<br>2. probable; likely<br>3. passionate; sensuous; hot", CardDefinitions.shortDefinition(senses))
    }

    @Test
    fun `a short definition of one sense has no number`() {
        assertEquals("&lt;bells&gt;", CardDefinitions.shortDefinition(listOf(listOf("(sound of) <bells>"))))
        assertEquals("(onomatopoeia)", CardDefinitions.shortDefinition(listOf(listOf("(onomatopoeia)"))))
        assertEquals("talent; ability", CardDefinitions.shortDefinition(listOf(emptyList(), listOf("talent", "ability"))))
        assertEquals("", CardDefinitions.shortDefinition(emptyList()))
    }

    @Test
    fun `a dictionary without glosses leaves the short definition to the next`() {
        val glossaries = listOf(
            GlossaryEntry("Prose", """[{"type":"structured-content","content":{"tag":"div","content":"a paragraph"}}]"""),
            GlossaryEntry("Terms", structured(listOf("certain death"))),
        )
        assertEquals("certain death", CardDefinitions.selectionText(glossaries))
    }

    @Test
    fun `the forms entry is not a meaning`() {
        val glossaries = listOf(
            GlossaryEntry("Terms", structured(listOf("desperate", "frantic"))),
            GlossaryEntry("Terms", """["必死（★）","必至（🅁）"]""", definitionTags = "forms"),
        )
        assertEquals("desperate; frantic", CardDefinitions.selectionText(glossaries))
    }

    @Test
    fun `examples, notes, forms and cross-references never reach the short definition`() {
        val glossaries = listOf(
            GlossaryEntry("Terms", structured(listOf("deep", "dark"), examples)),
            GlossaryEntry("Terms", structured(listOf("strong"), jitendexNoise)),
        )
        assertEquals("1. deep; dark<br>2. strong", CardDefinitions.selectionText(glossaries))
    }

    @Test
    fun `numbered text senses lose their bracketed notes`() {
        assertEquals(listOf(listOf("trial", "test", "attempt"), listOf("sample", "specimen")), CardDefinitions.glosses(numberedText))
        assertEquals("1. trial; test; attempt<br>2. sample; specimen", CardDefinitions.selectionText(listOf(GlossaryEntry("Text", numberedText))))
    }

    @Test
    fun `text without numbered lines is one sense`() {
        assertEquals(listOf(listOf("a whole definition")), CardDefinitions.glosses("""["a whole definition"]"""))
    }

    @Test
    fun `glosses are escaped`() {
        val glossaries = listOf(GlossaryEntry("Terms", structured(listOf("R&D", "a<b"))))
        assertEquals("R&amp;D; a&lt;b", CardDefinitions.selectionText(glossaries))
        assertTrue(CardDefinitions.mainDefinition(glossaries)!!.contains("<li>R&amp;D</li><li>a&lt;b</li>"))
    }

    @Test
    fun `the main definition is the first dictionary's senses with their glosses only`() {
        val glossaries = listOf(
            GlossaryEntry("Terms", structured(listOf("deep", "dark"), examples), definitionTags = "adj-i"),
            GlossaryEntry("Terms", structured(listOf("strong"), jitendexNoise)),
            GlossaryEntry("Terms", """["spelling"]""", definitionTags = "forms"),
            GlossaryEntry("Text", numberedText),
        )
        assertEquals(
            "<div style=\"text-align: left;\" class=\"yomitan-glossary\"><ol>" +
                "<li data-dictionary=\"Terms\"><i>(adj-i, Terms)</i> <ul><li>deep</li><li>dark</li></ul></li>" +
                "<li data-dictionary=\"Terms\"><i>(Terms)</i> <ul><li>strong</li></ul></li>" +
                "</ol></div>",
            CardDefinitions.mainDefinition(glossaries),
        )
    }

    @Test
    fun `a text dictionary first gives its senses as a numbered list`() {
        val main = CardDefinitions.mainDefinition(listOf(GlossaryEntry("Text", numberedText)))!!
        assertTrue(main.contains("<ol><li>trial; test; attempt</li><li>sample; specimen</li></ol>"))
        assertFalse(main.contains("〔"))
    }

    @Test
    fun `a first dictionary with no glosses at all keeps the full entries`() {
        val prose = listOf(GlossaryEntry("Prose", """[{"type":"structured-content","content":{"tag":"div","content":"a paragraph"}}]"""))
        assertNull(CardDefinitions.mainDefinition(prose))
        val hit = TermHit(matched = "語", deinflected = "語", expression = "語", glossaries = prose)
        val source = MineSource(hit, "語", 0, 1, glossaryHtml = "<ol><li>all</li></ol>", firstGlossaryHtml = "<ol><li>full first</li></ol>", furiganaPlain = "語", gameTitle = "")
        val fields = CardFields.lapis(source)
        assertEquals("<ol><li>full first</li></ol>", fields["MainDefinition"])
        assertEquals("<ol><li>all</li></ol>", fields["Glossary"])
    }

    @Test
    fun `a card gets the short definition, a clean main definition and the full glossary`() {
        val glossaries = listOf(GlossaryEntry("Terms", structured(listOf("deep", "dark"), examples)))
        val hit = TermHit(matched = "語", deinflected = "語", expression = "語", glossaries = glossaries)
        val source = MineSource(hit, "語", 0, 1, glossaryHtml = "<ol><li>everything</li></ol>", firstGlossaryHtml = "<ol><li>first</li></ol>", furiganaPlain = "語", gameTitle = "")
        val fields = CardFields.lapis(source)
        assertEquals("deep; dark", fields["SelectionText"])
        assertFalse(fields["MainDefinition"]!!.contains("例文"))
        assertEquals("<ol><li>everything</li></ol>", fields["Glossary"])
    }

    /** A made-up text entry in 新和英's layout without sense numbers: a headword, then long lines. */
    private val longUnnumbered = "[\"ためし【試し】 [ローマ字](tameshi)\\n" +
        "〔使い方〕 a trial run of something new, as one makes before deciding whether to keep it at all.\\n" +
        "《分野》 a test.\\n" + "an attempt.\\n" + "a sample (aside).\\n" + "a specimen.\\n" + "a long tail of further usage notes.\"]"

    @Test
    fun `unnumbered text gives a short definition, a gloss per line without the headword`() {
        val glossaries = listOf(GlossaryEntry("Text", longUnnumbered))
        val short = CardDefinitions.selectionText(glossaries)
        assertEquals(
            "a trial run of something new, as one makes before deciding whether to keep it a…; a test; an attempt",
            short,
        )
        assertFalse(short.contains("【"))
        val main = CardDefinitions.mainDefinition(glossaries)!!
        assertFalse(main.contains("ローマ字"))
        assertFalse(main.contains("試し"))
        assertTrue(main.contains("<li>a test</li><li>an attempt</li><li>a sample</li>"))
    }

    @Test
    fun `a line that is the entry's own word is its headword`() {
        val entry = GlossaryEntry("Text", "[\"試し ためし\\nan attempt.\"]")
        assertEquals("an attempt", CardDefinitions.selectionText(listOf(entry), headwords = listOf("試し", "ためし")))
        assertEquals("試し ためし; an attempt", CardDefinitions.selectionText(listOf(entry)))
    }

    @Test
    fun `a long gloss is cut at 80 characters`() {
        val long = "x".repeat(120)
        val short = CardDefinitions.shortDefinition(listOf(listOf(long)))
        assertEquals(80, short.length)
        assertTrue(short.endsWith("…"))
    }
}
