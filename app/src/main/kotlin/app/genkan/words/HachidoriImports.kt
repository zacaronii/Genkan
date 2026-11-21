package app.genkan.words

import android.content.Context
import android.os.Environment
import android.util.Log
import app.genkan.dictionary.Dictionaries
import app.genkan.dictionary.HachidoriArchive
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A dictionary a Hachidori host serves, and whether this device has it at that revision. */
data class HostDictionary(val id: String, val title: String, val revision: String, val installed: Boolean)

/**
 * Brings Hachidori's dictionaries and backups to Genkan: a linked host's dictionaries, each as
 * its compiled folder from `GET /dictionaries/<id>` (the relay's API port), and a backup file
 * from Download, whose dictionaries, marked words and lookup counts are added to Genkan's
 * (merged, not replaced as Hachidori's own restore does).
 */
@Singleton
class HachidoriImports @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttp: OkHttpClient,
    private val link: HachidoriLink,
    private val dictionaries: Dictionaries,
    private val words: WordStatuses,
    private val counts: LookupCounts,
) {
    private val client = okHttp.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private val _progress = MutableStateFlow<String?>(null)
    /** What an import is doing, or did last. */
    val progress: StateFlow<String?> = _progress.asStateFlow()

    /** The linked host's dictionaries; throws when it can't be reached. */
    suspend fun hostDictionaries(): List<HostDictionary> = withContext(Dispatchers.IO) {
        val base = link.config.first().apiBase ?: throw IOException("no Hachidori host set")
        val installed = dictionaries.installed()
        client.newCall(Request.Builder().url("$base/dictionaries").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val list = JSONObject(response.body.string()).optJSONArray("dictionaries") ?: return@use emptyList()
            List(list.length()) { i ->
                val d = list.getJSONObject(i)
                HostDictionary(d.getString("id"), d.optString("title"), d.optString("revision"), installed[d.optString("title")] == d.optString("revision"))
            }
        }
    }

    /** Downloads [dictionary] from the host and installs it. */
    suspend fun get(dictionary: HostDictionary) = withContext(Dispatchers.IO) {
        val base = link.config.first().apiBase ?: return@withContext
        val file = File(context.cacheDir, "hachidori-${dictionary.id}.zip")
        try {
            _progress.value = "Downloading ${dictionary.title}…"
            client.newCall(Request.Builder().url("$base/dictionaries/${dictionary.id}").build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                file.outputStream().use { response.body.byteStream().copyTo(it) }
            }
            _progress.value = "Installing ${dictionary.title}…"
            HachidoriArchive(file).use { archive ->
                archive.dictionaries.forEach { dictionaries.install(archive, it, "Hachidori host") }
            }
            _progress.value = "Installed ${dictionary.title} (${dictionary.revision})"
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't get ${dictionary.title}", e)
            _progress.value = "Couldn't get ${dictionary.title}: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            file.delete()
        }
    }

    /** The newest Hachidori backup in Download, or null. */
    suspend fun findBackup(): File? = withContext(Dispatchers.IO) {
        val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        download.walkTopDown().maxDepth(2)
            .filter { it.isFile && it.extension.equals("zip", ignoreCase = true) && "hachidori" in it.name.lowercase() }
            .filter(HachidoriArchive::isArchive)
            .maxByOrNull { it.lastModified() }
    }

    /** Imports [file]: its dictionaries, marked words and lookup counts, added to Genkan's. */
    suspend fun importBackup(file: File) = withContext(Dispatchers.IO) {
        try {
            HachidoriArchive(file).use { archive ->
                var installed = 0
                archive.dictionaries.forEachIndexed { i, d ->
                    _progress.value = "Installing ${d.title} (${i + 1} of ${archive.dictionaries.size})…"
                    if (dictionaries.install(archive, d, file.name)) installed++
                }
                val marks = words.importMarks(archive.known, archive.ignored)
                val rows = counts.importRows(archive.counts.map { Triple(it.term to it.reading, it.count, it.first to it.last) })
                _progress.value = "From ${file.name}: $installed dictionar${if (installed == 1) "y" else "ies"} installed " +
                    "(${archive.dictionaries.size - installed} already here), $marks marked words, $rows lookup counts"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't import ${file.name}", e)
            _progress.value = "Couldn't import ${file.name}: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private companion object {
        const val TAG = "HachidoriImports"
    }
}
