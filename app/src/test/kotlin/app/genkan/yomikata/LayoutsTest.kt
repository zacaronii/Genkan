package app.genkan.yomikata

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Genkan's layout reader against Yomikata's reference reader (tools/src/yomikata/layout.py):
 * each vector in test resources/layout-vectors is a layout, memory and what read_layout gave.
 */
class LayoutsTest {

    private val vectors: List<JSONObject> =
        File(javaClass.classLoader!!.getResource("layout-vectors")!!.toURI())
            .listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
            .map { JSONObject(it.readText()) }

    /** Reads the vector's regions; outside them, a read fails as the reference's does. */
    private fun memory(vector: JSONObject) = LayoutMemory { address, size ->
        val regions = vector.getJSONArray("memory")
        for (i in 0 until regions.length()) {
            val r = regions.getJSONObject(i)
            val base = r.getString("address").removePrefix("0x").toLong(16)
            val data = r.getString("hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (address >= base && address + size <= base + data.size) {
                return@LayoutMemory data.copyOfRange((address - base).toInt(), (address - base).toInt() + size)
            }
        }
        throw LayoutError("0x%08X+%d is outside memory".format(address, size))
    }

    /** [read] as the reference writes it out. */
    private fun asReference(read: LayoutRead): JSONObject = JSONObject().apply {
        read.message?.let { put("message", it) }
        read.speakerName?.let { put("speaker", it) }
        read.speakerNumber?.let { put("speaker", it) }
        read.shown?.let { put("shown", it) }
        read.choiceCount?.let { put("choice_count", it) }
        read.choiceCursor?.let { put("choice_cursor", it) }
        read.waiting?.let { put("waiting", it) }
        read.choosing?.let { put("choosing", it) }
        read.choices?.let { put("choices", org.json.JSONArray(it)) }
        read.onScreen?.let { put("on_screen", it) }
        if (read.errors.isNotEmpty()) put("errors", JSONObject(read.errors as Map<*, *>))
    }

    @Test
    fun `reads what the reference reader reads`() {
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors) {
            val entry = vector.getJSONObject("entry")
            // The entry's tables, in the order it lists them, from the vector's path -> text.
            val tables = vector.optJSONObject("tables")
            val paths = entry.optJSONArray("tables")
            val table = paths?.let { p -> CharTable.parse(List(p.length()) { tables!!.getString(p.getString(it)) }) }
            val controls = entry.optJSONObject("controls")?.let { c -> c.keys().asSequence().associate { it.uppercase() to c.getString(it) } }.orEmpty()
            val cleanup = CleanupStep.parse(entry.optJSONArray("cleanup"))
            val read = Layouts.read(vector.getJSONObject("layout"), memory(vector), entry.optInt("unit", 2), table, controls, cleanup)
            val expected = vector.getJSONObject("expected")
            val actual = asReference(read)
            val name = vector.getString("name")
            assertEquals(name, expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
            for (key in expected.keys().asSequence()) {
                // Which fields failed is normative; the messages aren't.
                if (key == "errors") {
                    assertEquals("$name: errors", expected.getJSONObject(key).keys().asSequence().toSet(), actual.getJSONObject(key).keys().asSequence().toSet())
                } else {
                    assertEquals("$name: $key", expected.get(key).toString(), actual.get(key).toString())
                }
            }
        }
    }

    private fun bytesAt(base: Long, vararg data: Int) = LayoutMemory { address, size ->
        val bytes = data.map { it.toByte() }.toByteArray()
        val start = (address - base).toInt()
        if (start < 0 || start + size > bytes.size) throw LayoutError("outside")
        bytes.copyOfRange(start, start + size)
    }

    private fun message(spec: String, memory: LayoutMemory): String? =
        Layouts.read(JSONObject("{\"message\": $spec}"), memory).message

    @Test
    fun `a huge length is clamped, not turned negative`() {
        val memory = bytesAt(0x100, 0x41, 0x42, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF)
        val spec = """{"address": "0x100", "charset": "utf8", "max_bytes": 2, "length": {"address": "0x104", "type": "u32"}}"""
        assertEquals("AB", message(spec, memory))
    }

    @Test
    fun `a character that doesn't decode is one replacement character`() {
        val sjis = """{"address": "0x0", "charset": "shift_jis", "max_bytes": 3}"""
        // 81 20 is a Shift-JIS pair that means nothing: one U+FFFD, then A.
        assertEquals("�A", message(sjis, bytesAt(0, 0x81, 0x20, 0x41)))
        // Single bytes 0x80 and 0xFD are U+FFFD whatever the decoder makes of them.
        assertEquals("�A�", message(sjis, bytesAt(0, 0x80, 0x41, 0xFD)))
    }

    @Test
    fun `a UTF-16 surrogate pair is one character`() {
        // 😀 (U+1F600) is D83D DE00 in memory as 3D D8 00 DE.
        val pair = bytesAt(0, 0x3D, 0xD8, 0x00, 0xDE, 0x41, 0x00)
        assertEquals("😀A", message("""{"address": "0x0", "charset": "utf16le", "max_bytes": 6}""", pair))
        // Three bytes can't hold the pair: its first half alone, a lone surrogate, is one U+FFFD.
        assertEquals("\uFFFD", message("""{"address": "0x0", "charset": "utf16le", "max_bytes": 3}""", pair))
        // A lone high surrogate is one U+FFFD.
        assertEquals("�A", message("""{"address": "0x0", "charset": "utf16le", "max_bytes": 4}""", bytesAt(0, 0x3D, 0xD8, 0x41, 0x00)))
    }

    @Test
    fun `the layout naming a version wins, else the first naming none`() {
        val general = JSONObject("""{"poll": 20}""")
        val specific = JSONObject("""{"versions": ["abc"], "poll": "frame"}""")
        assertEquals(specific, Layouts.pick(listOf(general, specific), "abc"))
        assertEquals(general, Layouts.pick(listOf(general, specific), "other"))
        assertEquals(null, Layouts.pick(listOf(specific), null))
    }
}
