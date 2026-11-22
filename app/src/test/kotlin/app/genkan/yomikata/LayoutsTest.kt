package app.genkan.yomikata

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            val entryFiles = paths?.let { p -> List(p.length()) { tables!!.getString(p.getString(it)) } }.orEmpty()
            val table = paths?.let { CharTable.parse(entryFiles) }
            // A layout's own set: the entry's tables, then the set's files.
            val withSet = { set: List<String> -> CharTable.parse(entryFiles + set.map { tables!!.getString(it) }) }
            val controls = entry.optJSONObject("controls")?.let { c -> c.keys().asSequence().associate { it.uppercase() to c.getString(it) } }.orEmpty()
            val cleanup = CleanupStep.parse(entry.optJSONArray("cleanup"))
            // One layout, or a version's several: the first whose own `when` is true is read.
            val layout = vector.optJSONObject("layout")
                ?: vector.getJSONArray("layouts").let { a -> Layouts.liveOf(List(a.length()) { a.getJSONObject(it) }, memory(vector))!! }
            val read = Layouts.read(layout, memory(vector), entry.optInt("unit", 2), table, controls, cleanup, withSet)
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

    /** Yomikata's tools/tests/test_layout.py, test_pick_without_a_hash_takes_the_only_version_of_the_release. */
    @Test
    fun `without a hash, the only version listed for the release id stands in`() {
        val b = "b".repeat(40)
        val c = "c".repeat(40)
        val layouts = """[{"notes": "general"}, {"versions": ["$b"], "notes": "b"}, {"versions": ["$c"], "notes": "c"}]"""
        fun entry(versions: String, id: String = "SLPS-00001", lays: String = layouts) = CatalogEntry.parse(
            """{"id": "$id", "platform": "ps1", "title": "t", "encoding": "table", "aliases": ["SLPS-00002"],
                "versions": $versions, "layouts": $lays}""",
        )
        fun notes(e: CatalogEntry, release: String?, hash: String? = null) = Layouts.pick(e, release, hash)?.optString("notes")

        val e = entry("""[{"exe_sha1": "$b"}, {"exe_sha1": "$c", "id": "SLPS-00002"}]""")
        assertEquals("b", notes(e, "SLPS-00001")) // a version without an id is the entry's own
        assertEquals("c", notes(e, "SLPS-00002"))
        assertEquals("c", notes(e, "SLPS-00001", c)) // a hash, when there is one, decides
        assertEquals("general", notes(e, "SLPS-00003")) // an id with no version listed
        val two = entry("""[{"exe_sha1": "$b"}, {"exe_sha1": "$c", "id": "SLPS-00002"}, {"exe_sha1": "${"d".repeat(40)}", "note": "rev 1"}]""")
        assertEquals("general", notes(two, "SLPS-00001")) // two versions of one id: no guessing
        // Only layouts naming versions, and no single version to name: none.
        val pinnedOnly = entry("""[{"exe_sha1": "$b"}, {"exe_sha1": "${"d".repeat(40)}"}]""", lays = """[{"versions": ["$b"], "notes": "b"}]""")
        assertEquals(null, notes(pinnedOnly, "SLPS-00001"))
        val cart = entry("""[{"header_crc32": "00000000"}]""", id = "AXXJ")
        assertEquals("general", notes(cart, "AXXJ")) // no executable hash to name
    }

    /** Yomikata's `understood`: a layout with a key or named value this reader doesn't know is not there. */
    @Test
    fun `a layout with anything unknown is passed over`() {
        val known = JSONObject("""{"poll": "frame", "message": {"address": "0x0", "charset": "utf16le", "max_bytes": 8,
            "rows": {"count": 2, "stride": 4, "lengths": {"address": "0x10", "counts": "characters"}},
            "when": {"address": "0x20", "equals": 2, "mask": 3}}, "waiting": {"address": "0x30", "mask": 3}}""")
        assertTrue(Layouts.understood(known))
        val paged = JSONObject(known.toString()).also {
            it.getJSONObject("message").remove("rows")
            it.getJSONObject("message").put("pages", JSONObject("""{"break": "0A00", "cursor": {"address": "0x40", "type": "u32"}}"""))
        }
        assertTrue(Layouts.understood(paged))
        // Codes with a table (controls that take arguments), a script page, a layout's table sets.
        val scripted = JSONObject(known.toString()).also {
            it.getJSONObject("message").put("charset", "table").remove("rows")
            it.getJSONObject("message").put("codes", JSONObject("""{"FFF9": 4}"""))
            it.getJSONObject("message").put("script", JSONObject("""{"open": "8000", "offset": {"address": "0x40", "type": "s32", "counts": "codes"}, "texts": "rows"}"""))
            it.put("tables", JSONObject("""{"by": {"address": "0x50", "type": "u32"}, "sets": {"36": ["a.tbl"], "-1": ["b.tbl", "c.tbl"]}}"""))
        }
        assertTrue(Layouts.understood(scripted))
        // A script's `rest` is a whole number of codes from 1 to 16.
        fun rested(rest: Any) = JSONObject(scripted.toString()).also { it.getJSONObject("message").getJSONObject("script").put("rest", rest) }
        assertTrue(Layouts.understood(rested(1)) && Layouts.understood(rested(16)))
        for (rest in listOf<Any>(0, 17, -1, 2.5, "2")) assertFalse("rest $rest", Layouts.understood(rested(rest)))
        // A script without open is one text from its start; texts and rest need open.
        fun unopened(edit: (JSONObject) -> Unit = {}) = JSONObject(scripted.toString()).also {
            it.getJSONObject("message").getJSONObject("script").remove("open")
            it.getJSONObject("message").getJSONObject("script").remove("texts")
            edit(it.getJSONObject("message").getJSONObject("script"))
        }
        assertTrue(Layouts.understood(unopened()))
        assertFalse(Layouts.understood(unopened { it.put("texts", "rows") }))
        assertFalse(Layouts.understood(unopened { it.put("rest", 2) }))
        // rows.pointers may only be true, and not with rows.lengths.
        fun pointed(value: Any, lengths: Boolean = false) = JSONObject(known.toString()).also {
            val rows = it.getJSONObject("message").getJSONObject("rows").put("pointers", value)
            if (!lengths) rows.remove("lengths")
        }
        assertTrue(Layouts.understood(pointed(true)))
        assertFalse(Layouts.understood(pointed(false)))
        assertFalse(Layouts.understood(pointed("true")))
        assertFalse(Layouts.understood(pointed(true, lengths = true)))
        fun changed(edit: (JSONObject) -> Unit) = JSONObject(known.toString()).also(edit)
        val unknown = listOf(
            changed { it.put("typing_speed", JSONObject()) },
            changed { it.getJSONObject("message").put("fade", 1) },
            changed { it.getJSONObject("message").getJSONObject("rows").put("gap", 2) },
            changed { it.getJSONObject("message").getJSONObject("rows").getJSONObject("lengths").put("counts", "glyphs") },
            changed { it.getJSONObject("message").put("charset", "big5") },
            changed { it.getJSONObject("waiting").put("type", "u64") },
            // A mask is a whole number, not below zero.
            *listOf("-1", "1.5", "\"3\"").map { mask -> changed { it.getJSONObject("waiting").put("mask", JSONObject("""{"m": $mask}""").get("m")) } }.toTypedArray(),
            // `counts` belongs to lengths, not to a flag.
            changed { it.getJSONObject("waiting").put("counts", "characters") },
            // Pages and rows never go together, and pages has exactly a break and a cursor.
            changed { it.getJSONObject("message").put("pages", JSONObject("""{"break": "0A00", "cursor": {"address": "0x40"}}""")) },
            changed {
                it.getJSONObject("message").remove("rows")
                it.getJSONObject("message").put("pages", JSONObject("""{"break": "0A00", "cursor": {"address": "0x40"}, "skip": 1}"""))
            },
            // A break of whole hex bytes only.
            *listOf("\"\"", "\"0\"", "12").map { brk ->
                changed {
                    it.getJSONObject("message").remove("rows")
                    it.getJSONObject("message").put("pages", JSONObject("""{"break": $brk, "cursor": {"address": "0x40"}}"""))
                }
            }.toTypedArray(),
            // Codes: whole hex leads of 1-4 bytes, integer lengths from the lead's up to 16, never with a table.
            *listOf("{}", """{"0": 2}""", """{"03": 1.5}""", """{"0300": 1}""", """{"03": 17}""", """{"0102030405": 6}""").map { codes ->
                changed { it.getJSONObject("message").put("codes", JSONObject(codes)) }
            }.toTypedArray(),
            // A script only with a table, without rows; open whole hex bytes; offset a number.
            changed { it.getJSONObject("message").put("script", JSONObject("""{"open": "8000", "offset": {"address": "0x40"}}""")) },
            changed {
                it.getJSONObject("message").put("charset", "table").remove("rows")
                it.getJSONObject("message").put("script", JSONObject("""{"open": "8", "offset": {"address": "0x40"}}"""))
            },
            changed {
                it.getJSONObject("message").put("charset", "table").remove("rows")
                it.getJSONObject("message").put("script", JSONObject("""{"open": "8000", "offset": {"address": "0x40", "counts": "characters"}}"""))
            },
            // texts, when given, is "rows".
            changed {
                it.getJSONObject("message").put("charset", "table").remove("rows")
                it.getJSONObject("message").put("script", JSONObject("""{"open": "8000", "offset": {"address": "0x40"}, "texts": "columns"}"""))
            },
            // A layout's tables: a number and at least one set of files, by decimal values.
            changed { it.put("tables", JSONObject("""{"by": {"address": "0x50"}, "sets": {}}""")) },
            changed { it.put("tables", JSONObject("""{"by": {"address": "0x50"}, "sets": {"x1": ["a.tbl"]}}""")) },
            changed { it.put("tables", JSONObject("""{"by": {"address": "0x50"}, "sets": {"1": []}}""")) },
        )
        for (layout in unknown) {
            assertEquals(layout.toString(), false, Layouts.understood(layout))
            assertEquals(null, Layouts.pick(listOf(layout), null))
        }
        // The next layout the reader knows is picked instead.
        assertEquals(known, Layouts.pick(listOf(unknown[0], known), null))
    }

    @Test
    fun `a window waiting or choosing with nothing in it waits on nothing and asks nothing`() {
        assertEquals(false, LayoutRead(message = "", waiting = true).waitingOnLine)
        assertEquals(true, LayoutRead(message = "「はい」", waiting = true).waitingOnLine)
        assertEquals(false, LayoutRead(choosing = true, choices = emptyList()).asking)
        assertEquals(false, LayoutRead(choosing = true).asking)
        assertEquals(true, LayoutRead(choosing = true, choices = listOf("はい", "いいえ")).asking)
        assertEquals(false, LayoutRead(choosing = false, choices = listOf("はい")).asking)
    }

    @Test
    fun `a version's layouts come in the entry's order, those naming it before the general ones`() {
        val a = JSONObject("""{"message": {"address": "0x0", "charset": "utf8", "max_bytes": 4}}""")
        val b = JSONObject("""{"message": {"address": "0x10", "charset": "utf8", "max_bytes": 4}}""")
        val mine = JSONObject("""{"versions": ["aa"], "message": {"address": "0x20", "charset": "utf8", "max_bytes": 4}}""")
        val unknown = JSONObject("""{"message": {"address": "0x30", "charset": "utf8", "max_bytes": 4}, "typing_speed": {}}""")
        assertEquals(listOf(a, b), Layouts.layoutsFor(listOf(a, unknown, mine, b), null))
        assertEquals(listOf(mine), Layouts.layoutsFor(listOf(a, mine, b), "aa"))
        // Parsed anew: compared as text (JSONObject compares by identity).
        assertEquals(listOf(a, b).map { it.toString() }, Layouts.parseLayouts(org.json.JSONArray(listOf(a, b)).toString()).map { it.toString() })
        assertEquals(listOf(a.toString()), Layouts.parseLayouts(a.toString()).map { it.toString() })
    }

    @Test
    fun `a layout whose when can't be read is passed over`() {
        val unread = JSONObject("""{"when": {"address": "0x9000", "equals": 1}, "message": {"address": "0x10", "charset": "utf8", "max_bytes": 4}}""")
        val second = JSONObject("""{"when": {"address": "0x10", "equals": 65}, "message": {"address": "0x10", "charset": "utf8", "max_bytes": 4}}""")
        val memory = bytesAt(0x10, 'A'.code, 'B'.code, 0, 0)
        assertEquals(false, Layouts.live(unread, memory))
        assertEquals(second, Layouts.liveOf(listOf(unread, second), memory))
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
