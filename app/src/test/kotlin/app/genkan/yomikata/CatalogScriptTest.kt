package app.genkan.yomikata

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogScriptTest {

    private val record = JSONObject(
        """{"path":"scripts/gc/GTST01.lua","sha256":"ab12","platform":"gc","title":"A made-up game","reads":"its bubbles",
           "builds":["GTST01-00"],"needs":[],"runtime":1,"data":{"tbl":{"path":"scripts/gc/GTST01.tbl","sha256":"cd34"}}}""",
    )

    @Test
    fun `an index record reads as the script and its data files`() {
        val script = CatalogScript.parse("GTST01", record)
        assertEquals("scripts/gc/GTST01.lua", script.path)
        assertEquals(listOf("GTST01-00"), script.builds)
        assertEquals(1, script.runtime)
        assertEquals("scripts/gc/GTST01.tbl" to "cd34", script.data["tbl"])
    }

    @Test
    fun `a script Genkan can't run is left alone, and says why`() {
        val script = CatalogScript.parse("GTST01", record)
        assertNull(script.unusable(level = 1, buildId = "GTST01-00", regions = emptySet()))
        // Unknown build (a host that can't tell): the script's own check decides.
        assertNull(script.unusable(level = 1, buildId = null, regions = emptySet()))
        assertTrue(script.unusable(level = 1, buildId = "GTST01-01", regions = emptySet())!!.contains("GTST01-01"))
        assertTrue(script.copy(runtime = 2).unusable(level = 1, buildId = null, regions = emptySet())!!.contains("runtime 2"))
        assertTrue(script.copy(needs = listOf("vram")).unusable(level = 1, buildId = null, regions = emptySet())!!.contains("vram"))
        assertNull(script.copy(needs = listOf("vram")).unusable(level = 1, buildId = null, regions = setOf("vram")))
    }

    @Test
    fun `the source and every data file come along, and a missing one means no script`() {
        val script = CatalogScript.parse("GTST01", record)
        val asked = mutableListOf<String>()
        val found = script.files { path, _ -> asked += path; path.toByteArray() }
        assertEquals(listOf("scripts/gc/GTST01.lua", "scripts/gc/GTST01.tbl"), asked)
        val files = (found as ScriptLookup.Found).files
        assertEquals("scripts/gc/GTST01.tbl", String(files.data.getValue("tbl")))
        // The table can't be had: the game takes its JSON layout instead.
        val missing = script.files { path, _ -> if (path.endsWith(".tbl")) throw java.io.IOException("couldn't fetch $path") else ByteArray(1) }
        assertTrue((missing as ScriptLookup.Failed).reason.contains(".tbl"))
    }

    @Test
    fun `a file that moved past the index's hash says the index is behind`() {
        val lookup = CatalogScript.parse("GTST01", record).files { path, _ -> throw IndexBehind("$path doesn't match") }
        assertTrue((lookup as ScriptLookup.Failed).indexBehind)
        val offline = CatalogScript.parse("GTST01", record).files { path, _ -> throw java.io.IOException("no network for $path") }
        assertTrue(!(offline as ScriptLookup.Failed).indexBehind)
    }

    @Test
    fun `the index is fetched again for a missing id only when it's ten minutes old, and at most that often`() {
        val minute = 60_000L
        val now = 1_000_000_000L
        // Fetched five minutes ago: kept.
        assertTrue(!IndexAge.fetchAgain(now, fetchedAtMs = now - 5 * minute, lastTriedMs = 0))
        // Fetched an hour ago, never tried since: fetched again.
        assertTrue(IndexAge.fetchAgain(now, fetchedAtMs = now - 60 * minute, lastTriedMs = 0))
        // Tried three minutes ago (offline, so the fetch time didn't move): not again yet.
        assertTrue(!IndexAge.fetchAgain(now, fetchedAtMs = now - 60 * minute, lastTriedMs = now - 3 * minute))
        assertTrue(IndexAge.fetchAgain(now, fetchedAtMs = now - 60 * minute, lastTriedMs = now - 11 * minute))
    }
}
