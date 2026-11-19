package app.genkan.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DictionaryFilesTest {

    @get:Rule val temp = TemporaryFolder()

    private fun zip(file: File, vararg entries: Pair<String, String>): File {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `reads title and revision from index json`() {
        val file = zip(temp.newFile("jmdict.zip"), "index.json" to """{"title":"JMdict","format":3,"revision":"2025-11-01"}""")
        assertEquals(DictionaryZip(file, "JMdict", "2025-11-01"), DictionaryFiles.read(file))
    }

    @Test
    fun `older dictionaries say version instead of format`() {
        val file = zip(temp.newFile("old.zip"), "index.json" to """{"title":"Old","version":1}""")
        assertEquals("Old", DictionaryFiles.read(file)?.title)
    }

    @Test
    fun `zips that are not dictionaries are left out`() {
        assertNull(DictionaryFiles.read(zip(temp.newFile("rom.zip"), "game.gba" to "x")))
        assertNull(DictionaryFiles.read(zip(temp.newFile("other.zip"), "index.json" to """{"name":"package"}""")))
        assertNull(DictionaryFiles.read(temp.newFile("broken.zip").apply { writeText("not a zip") }))
    }

    @Test
    fun `finds zips in the folder and one level down, in path order`() {
        val root = temp.newFolder("Download")
        zip(File(root, "JP Dictionaries/[Freq] JPDB.zip"), "index.json" to """{"title":"JPDB","format":3}""")
        zip(File(root, "JP Dictionaries/[Bilingual] JMdict.zip"), "index.json" to """{"title":"JMdict","format":3}""")
        zip(File(root, "loose.zip"), "index.json" to """{"title":"Loose","format":3}""")
        zip(File(root, "a/b/too deep.zip"), "index.json" to """{"title":"Deep","format":3}""")
        File(root, "notes.txt").writeText("hi")
        assertEquals(listOf("JMdict", "JPDB", "Loose"), DictionaryFiles.find(root).map { it.title })
    }
}
