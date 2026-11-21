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
            val table = vector.optString("table").takeIf { vector.has("table") && !vector.isNull("table") }?.let { CharTable.parse(listOf(it)) }
            val read = Layouts.read(vector.getJSONObject("layout"), memory(vector), entry.optInt("unit", 2), table)
            val expected = vector.getJSONObject("expected")
            val actual = asReference(read)
            assertEquals(vector.getString("name"), expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
            for (key in expected.keys().asSequence()) {
                assertEquals("${vector.getString("name")}: $key", expected.get(key).toString(), actual.get(key).toString())
            }
        }
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
