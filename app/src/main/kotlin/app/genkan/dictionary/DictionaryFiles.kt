package app.genkan.dictionary

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.File
import java.util.zip.ZipFile

/** A Yomitan dictionary zip found on storage, from its index.json. */
data class DictionaryZip(val file: File, val title: String, val revision: String)

@JsonClass(generateAdapter = true)
internal data class YomitanIndex(
    val title: String? = null,
    val revision: String? = null,
    val format: Int? = null,
    val version: Int? = null,
)

object DictionaryFiles {

    private val indexAdapter = Moshi.Builder().build().adapter(YomitanIndex::class.java)

    /**
     * Yomitan zips in [folder] and its direct subfolders, in path order (the order a person
     * gives files by naming them, "[Bilingual] …" before "[Freq] …").
     */
    fun find(folder: File): List<DictionaryZip> {
        val top = folder.listFiles().orEmpty().asSequence()
        return (top + top.filter { it.isDirectory }.flatMap { it.listFiles().orEmpty().asSequence() })
            .filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
            .sortedBy { it.path }
            .mapNotNull(::read)
            .toList()
    }

    /** The zip's title and revision, or null when it isn't a Yomitan dictionary. */
    fun read(file: File): DictionaryZip? = try {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("index.json") ?: return null
            val index = indexAdapter.fromJson(zip.getInputStream(entry).bufferedReader().readText()) ?: return null
            val title = index.title?.takeIf { it.isNotBlank() } ?: return null
            if (index.format == null && index.version == null) return null
            DictionaryZip(file, title, index.revision.orEmpty())
        }
    } catch (e: Exception) {
        null
    }
}
