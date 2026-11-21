package app.genkan.yomikata

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The layout conformance vectors (Yomikata's tools/tests/layout-vectors) on the device: its
 * Shift-JIS and EUC-JP decoders are Android's own, not the JDK's the unit tests use.
 */
@RunWith(AndroidJUnit4::class)
class LayoutVectorsOnDeviceTest {

    @Test
    fun everyVectorReadsAsTheReferenceDoes() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val names = assets.list("layout-vectors").orEmpty().filter { it.endsWith(".json") }.sorted()
        assertTrue(names.isNotEmpty())
        val failures = mutableListOf<String>()
        for (file in names) {
            val vector = JSONObject(assets.open("layout-vectors/$file").reader().readText())
            val entry = vector.getJSONObject("entry")
            val tables = vector.optJSONObject("tables")
            val paths = entry.optJSONArray("tables")
            val table = paths?.let { p -> CharTable.parse(List(p.length()) { tables!!.getString(p.getString(it)) }) }
            val controls = entry.optJSONObject("controls")?.let { c -> c.keys().asSequence().associate { it.uppercase() to c.getString(it) } }.orEmpty()
            val read = Layouts.read(
                vector.getJSONObject("layout"), memory(vector), entry.optInt("unit", 2), table, controls,
                CleanupStep.parse(entry.optJSONArray("cleanup")),
            )
            val expected = vector.getJSONObject("expected")
            val actual = asReference(read)
            val keys = expected.keys().asSequence().toSet()
            if (keys != actual.keys().asSequence().toSet()) failures += "$file: fields ${actual.keys().asSequence().toSet()}, expected $keys"
            for (key in keys) {
                if (!actual.has(key)) continue
                val same = if (key == "errors") {
                    expected.getJSONObject(key).keys().asSequence().toSet() == actual.getJSONObject(key).keys().asSequence().toSet()
                } else {
                    expected.get(key).toString() == actual.get(key).toString()
                }
                if (!same) failures += "$file: $key is ${actual.get(key)}, expected ${expected.get(key)}"
            }
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
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
        throw LayoutError("outside memory")
    }
}
