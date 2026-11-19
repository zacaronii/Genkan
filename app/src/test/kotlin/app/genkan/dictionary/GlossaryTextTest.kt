package app.genkan.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

class GlossaryTextTest {

    @Test
    fun `jmdict structured content gives its glossary items, not the examples`() {
        // As hoshidicts returned it for 眺める on the Thor.
        val json = """[{"content":[{"content":[{"content":"to look at","tag":"li"},{"content":"to gaze at","tag":"li"}],""" +
            """"data":{"content":"glossary"},"lang":"en","tag":"ul"},{"content":[{"content":"何時間も海を眺めた。","tag":"li"}],""" +
            """"data":{"content":"examples"},"tag":"ul"}],"type":"structured-content"}]"""
        assertEquals(listOf("to look at", "to gaze at"), GlossaryText.definitions(json))
    }

    @Test
    fun `plain string glossaries pass through`() {
        assertEquals(listOf("to eat", "to live on"), GlossaryText.definitions("""["to eat"," to live on "]"""))
    }

    @Test
    fun `unmarked structured content is flattened, ruby text left out`() {
        val json = """[{"type":"structured-content","content":[{"tag":"ruby","content":["漢",{"tag":"rt","content":"かん"}]},"字の説明"]}]"""
        assertEquals(listOf("漢字の説明"), GlossaryText.definitions(json))
    }

    @Test
    fun `text that is not json is kept as it is`() {
        assertEquals(listOf("not json ["), GlossaryText.definitions("not json ["))
    }
}
