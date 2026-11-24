package app.genkan.anki

import app.genkan.dictionary.GlossaryEntry
import app.genkan.dictionary.TermHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Card markup as the reference card has it (Amatsubame's renderer), from made-up entries in JMdict's and 新和英's shapes. */
class CardHtmlTest {

    /** A sense as JMdict writes it: a glossary list, then examples with their flags. */
    private val sense = """[{"type":"structured-content","content":[""" +
        """{"tag":"ul","lang":"en","data":{"content":"glossary"},"style":{"listStyleType":"circle"},"content":[{"tag":"li","content":"brightness"},{"tag":"li","content":"glow"}]},""" +
        """{"tag":"ul","lang":"ja","data":{"content":"examples"},"style":{"listStyleType":"'🇯🇵 '"},"content":[{"tag":"li","content":"例えの文。"},""" +
        """{"tag":"li","lang":"en","style":{"listStyleType":"'🇬🇧 '"},"content":"An example."}]}]}]"""

    private val senseHtml = "<div class=\"gloss-content\"><span class=\"structured-content\">" +
        "<ul class=\"gloss-sc-ul\" lang=\"en\" data-sc-content=\"glossary\" style=\"list-style-type: circle\"><li class=\"gloss-sc-li\">brightness</li><li class=\"gloss-sc-li\">glow</li></ul>" +
        "<ul class=\"gloss-sc-ul\" lang=\"ja\" data-sc-content=\"examples\" style=\"list-style-type: &#39;🇯🇵 &#39;\"><li class=\"gloss-sc-li\">例えの文。</li>" +
        "<li class=\"gloss-sc-li\" lang=\"en\" style=\"list-style-type: &#39;🇬🇧 &#39;\">An example.</li></ul></span></div>"

    @Test
    fun `a structured sense renders as the reference card has it`() {
        assertEquals(senseHtml, CardHtml.render(sense))
    }

    @Test
    fun `the main definition is the first dictionary's entries in full, labelled with the sense number first`() {
        val hit = TermHit(
            matched = "光", deinflected = "光", expression = "光", reading = "ひかり",
            glossaries = listOf(
                GlossaryEntry("Dict [2025-11-01]", sense, definitionTags = "1 n"),
                GlossaryEntry("Dict [2025-11-01]", """[{"type":"structured-content","content":{"tag":"ul","lang":"en","data":{"content":"glossary"},"content":{"tag":"li","content":"(made-up) sheen"}}}]""", definitionTags = "2 n n-suf"),
                GlossaryEntry("Text", "[\"ひかり【光】 [ローマ字](hikari)\\n【物】 light; a glow.\\n\"]"),
            ),
        )
        val fields = CardFields.lapis(MineSource(hit, "光", 0, 1, furiganaPlain = "光[ひかり]", gameTitle = ""))
        val first = "<li data-dictionary=\"Dict [2025-11-01]\"><i>(1, n, Dict [2025-11-01])</i> $senseHtml</li>" +
            "<li data-dictionary=\"Dict [2025-11-01]\"><i>(2, n, n-suf, Dict [2025-11-01])</i> <div class=\"gloss-content\"><span class=\"structured-content\">" +
            "<ul class=\"gloss-sc-ul\" lang=\"en\" data-sc-content=\"glossary\"><li class=\"gloss-sc-li\">(made-up) sheen</li></ul></span></div></li>"
        assertEquals("<div style=\"text-align: left;\" class=\"yomitan-glossary\"><ol>$first</ol></div>", fields["MainDefinition"])
        // Glossary: every dictionary, a text one as its text with its line breaks.
        assertEquals(
            "<div style=\"text-align: left;\" class=\"yomitan-glossary\"><ol>$first" +
                "<li data-dictionary=\"Text\"><i>(Text)</i> <div class=\"gloss-content\">ひかり【光】 [ローマ字](hikari)\n【物】 light; a glow.\n</div></li></ol></div>",
            fields["Glossary"],
        )
    }

    @Test
    fun `images are left out, links and tables kept as the renderer writes them`() {
        val json = """[{"type":"structured-content","content":[{"tag":"img","path":"pic.png"},""" +
            """{"tag":"a","href":"?query=%E5%85%89&wildcards=off","content":"光"},{"tag":"br"},""" +
            """{"tag":"table","content":{"tag":"tr","content":{"tag":"td","colSpan":2,"content":"cell"}}}]}]"""
        assertEquals(
            "<div class=\"gloss-content\"><span class=\"structured-content\">" +
                "<a class=\"gloss-link\" data-external=\"false\" data-lookup=\"光\"><span class=\"gloss-link-text\">光</span></a>" +
                "<br class=\"gloss-sc-br\">" +
                "<div class=\"gloss-sc-table-container\"><table class=\"gloss-sc-table\"><tr class=\"gloss-sc-tr\"><td class=\"gloss-sc-td\" colspan=\"2\">cell</td></tr></table></div>" +
                "</span></div>",
            CardHtml.render(json),
        )
        assertNull(CardHtml.renderOrNull("""[{"type":"image","path":"pic.png"}]"""))
        assertFalse(CardHtml.render("""[{"type":"image","path":"pic.png"}]""").contains("<img"))
    }

    @Test
    fun `several definitions become a list, and numeric margins are in em`() {
        assertEquals(
            "<ol class=\"gloss-list\"><li class=\"gloss-item\"><div class=\"gloss-content\">one</div></li><li class=\"gloss-item\"><div class=\"gloss-content\">two</div></li></ol>",
            CardHtml.render("""["one","two"]"""),
        )
        assertEquals("margin-top: 0.5em; margin-left: 2em", CardHtml.css(org.json.JSONObject("""{"marginTop":0.5,"marginLeft":2}""")))
    }
}
