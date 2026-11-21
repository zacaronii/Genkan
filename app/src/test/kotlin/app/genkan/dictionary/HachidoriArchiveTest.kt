package app.genkan.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HachidoriArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    // Shaped like Hachidori 0.3.0's test backup (test/data/backups/hachidori-0.3.0.zip).
    private val manifest = """
        {"format": "hachidori-backup", "version": 3, "createdAt": "2026-10-14T12:27:21.862Z",
         "snapshot": {"state": {"dictionaries": [
             {"title": "Hachidori Custom Dictionary", "revision": "r1", "enabled": true, "termCount": 1},
             {"title": "hachidori-fixture", "revision": "test-1", "enabled": false, "termCount": 6, "frequencyCount": 2, "pitchCount": 2, "kanjiCount": 1}]},
           "options": {"anki": {"apiKey": "secret"}},
           "wordStatusOverrides": {"revision": 1, "known": ["猫"], "ignored": ["犬", ""]}},
         "lookupStatsRows": [{"term": "食べる", "reading": "たべる", "lookupCount": 2, "firstLookedUpAt": 1791980841824, "lastLookedUpAt": 1791980841825}],
         "files": []}
    """.trimIndent()

    private fun archive(vararg extra: Pair<String, String>): File {
        val file = temp.newFile("backup.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("hachidori-backup.json", manifest)
            put("dictionaries/1/index.json", "{}")
            put("dictionaries/1/blobs.bin", "blob")
            put("dictionaries/1/.hoshidicts_6", "")
            extra.forEach { (name, text) -> put(name, text) }
        }
        return file
    }

    @Test
    fun `dictionaries, marks and counts come out of a backup`() {
        HachidoriArchive(archive()).use { a ->
            // Dictionary 0 has no folder in this archive: only what can be installed is listed.
            assertEquals(listOf("hachidori-fixture"), a.dictionaries.map { it.title })
            val d = a.dictionaries.single()
            assertEquals(1, d.ordinal)
            assertFalse(d.enabled)
            assertEquals(2L, d.pitches)
            assertEquals(listOf("猫"), a.known)
            assertEquals(listOf("犬"), a.ignored)
            assertEquals(2, a.counts.single().count)
            val into = temp.newFolder("out")
            a.extract(d, into)
            assertEquals("blob", File(into, "blobs.bin").readText())
            assertTrue(File(into, ".hoshidicts_6").isFile)
        }
    }

    @Test
    fun `a path out of the folder is refused`() {
        HachidoriArchive(archive("dictionaries/1/../../evil" to "x")).use { a ->
            assertThrows(IOException::class.java) { a.extract(a.dictionaries.single(), temp.newFolder("out")) }
        }
    }

    @Test
    fun `other zips are not Hachidori archives`() {
        val file = temp.newFile("other.zip")
        ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry("index.json")); it.closeEntry() }
        assertFalse(HachidoriArchive.isArchive(file))
        assertTrue(HachidoriArchive.isArchive(archive()))
    }
}
