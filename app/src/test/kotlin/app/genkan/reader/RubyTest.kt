package app.genkan.reader

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RubyTest {

    @Test
    fun `a script's code points become UTF-16 offsets`() {
        // 𠮷 is two UTF-16 units: the reading over 野 starts at 2.
        val ruby = Ruby.fromCodePoints("𠮷野家", JSONArray("""[[0,1,"よし"],[1,1,"の"],[2,1,"や"]]"""))
        assertEquals(listOf(Ruby(0, 2, "よし"), Ruby(2, 3, "の"), Ruby(3, 4, "や")), ruby)
        // Out of the text, empty, or without a reading: left out.
        assertTrue(Ruby.fromCodePoints("あ", JSONArray("""[[0,2,"x"],[0,0,"y"],[0,1,""]]""")).isEmpty())
    }

    @Test
    fun `furigana follow the line as the backlog normalises it`() {
        val original = "  古い港\n町に 灯った"
        val normalised = Lines.normalise(original)
        assertEquals("古い港町に灯った", normalised)
        val ruby = Ruby.remap(listOf(Ruby(2, 3, "ふる"), Ruby(4, 7, "みなとまち"), Ruby(9, 10, "とも")), original, normalised)
        assertEquals(listOf(Ruby(0, 1, "ふる"), Ruby(2, 4, "みなとまち"), Ruby(5, 6, "とも")), ruby)
        // Over nothing that survived (spaces): dropped.
        assertTrue(Ruby.remap(listOf(Ruby(0, 2, "x")), original, normalised).isEmpty())
    }

    @Test
    fun `furigana keep as the backlog stores them`() {
        val ruby = listOf(Ruby(0, 1, "ふる"), Ruby(2, 4, "みなとまち"))
        assertEquals(ruby, Ruby.fromJson(Ruby.toJson(ruby)))
        assertEquals(null, Ruby.toJson(emptyList()))
        assertTrue(Ruby.fromJson(null).isEmpty())
        assertTrue(Ruby.fromJson("not json").isEmpty())
    }
}
