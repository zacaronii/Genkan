package app.genkan.dictionary

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * A Hachidori archive: a backup from its settings, or one dictionary from a host's
 * `GET /dictionaries/<id>` (Hachidori's docs/backup-format.md). A stored ZIP64 with
 * `hachidori-backup.json` and each dictionary's compiled hoshidicts folder under
 * `dictionaries/<n>/`, `n` being its place in the snapshot's dictionary list. Genkan's engine is
 * the same hoshidicts fork, so the folders load as they are. The manifest's options carry the
 * host's AnkiConnect address and key; nothing here reads them.
 */
class HachidoriArchive(file: File) : Closeable {
    private val zip = ZipFile(file)
    private val manifest: JSONObject

    init {
        val entry = zip.getEntry(MANIFEST) ?: run {
            zip.close()
            throw IOException("${file.name} isn't a Hachidori archive (no $MANIFEST)")
        }
        manifest = zip.getInputStream(entry).use { JSONObject(it.reader().readText()) }
        if (manifest.optString("format") != "hachidori-backup") {
            zip.close()
            throw IOException("${file.name}: unknown format ${manifest.optString("format")}")
        }
    }

    private val snapshot: JSONObject get() = manifest.optJSONObject("snapshot") ?: JSONObject()

    /** One dictionary in the archive, with what its kinds hold. */
    data class Dictionary(
        val ordinal: Int,
        val title: String,
        val revision: String,
        val enabled: Boolean,
        val terms: Long,
        val frequencies: Long,
        val pitches: Long,
        val kanji: Long,
    )

    /** The dictionaries whose folders the archive has, in Hachidori's order. */
    val dictionaries: List<Dictionary> by lazy {
        val list = snapshot.optJSONObject("state")?.optJSONArray("dictionaries") ?: JSONArray()
        (0 until list.length()).mapNotNull { i ->
            val d = list.optJSONObject(i) ?: return@mapNotNull null
            if (zip.getEntry("$FOLDERS$i/index.json") == null) return@mapNotNull null
            Dictionary(
                ordinal = i,
                title = d.optString("title"),
                revision = d.optString("revision"),
                enabled = d.optBoolean("enabled", true),
                terms = d.optLong("termCount"),
                frequencies = d.optLong("frequencyCount"),
                pitches = d.optLong("pitchCount"),
                kanji = d.optLong("kanjiCount"),
            )
        }
    }

    /** The words marked known and ignored (backups from version 3 on). */
    val known: List<String> get() = snapshot.optJSONObject("wordStatusOverrides")?.optJSONArray("known").strings()
    val ignored: List<String> get() = snapshot.optJSONObject("wordStatusOverrides")?.optJSONArray("ignored").strings()

    /** Lookup counts: term, reading, count, first and last lookup (epoch ms). */
    data class Count(val term: String, val reading: String, val count: Int, val first: Long?, val last: Long?)

    val counts: List<Count>
        get() {
            val rows = manifest.optJSONArray("lookupStatsRows") ?: return emptyList()
            return (0 until rows.length()).mapNotNull { i ->
                val r = rows.optJSONObject(i) ?: return@mapNotNull null
                Count(
                    r.optString("term"),
                    r.optString("reading"),
                    r.optInt("lookupCount"),
                    r.optLong("firstLookedUpAt").takeIf { r.has("firstLookedUpAt") },
                    r.optLong("lastLookedUpAt").takeIf { r.has("lastLookedUpAt") },
                )
            }.filter { it.term.isNotEmpty() && it.count > 0 }
        }

    /** When the archive was made (ISO 8601). */
    val createdAt: String get() = manifest.optString("createdAt")

    /** Copies [dictionary]'s folder into [into]; never outside it. */
    fun extract(dictionary: Dictionary, into: File) {
        val prefix = "$FOLDERS${dictionary.ordinal}/"
        val root = into.canonicalFile
        for (entry in zip.entries()) {
            if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
            val target = File(root, entry.name.removePrefix(prefix)).canonicalFile
            if (!target.path.startsWith(root.path + File.separator)) throw IOException("bad path in archive: ${entry.name}")
            target.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }

    override fun close() = zip.close()

    companion object {
        const val MANIFEST = "hachidori-backup.json"
        private const val FOLDERS = "dictionaries/"

        /** Whether [file] looks like a Hachidori archive (a zip holding the manifest). */
        fun isArchive(file: File): Boolean = runCatching { ZipFile(file).use { it.getEntry(MANIFEST) != null } }.getOrDefault(false)

        private fun JSONArray?.strings(): List<String> = this?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty().filter { it.isNotBlank() }
    }
}
