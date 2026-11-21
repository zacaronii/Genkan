package app.genkan.anki

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** AnkiConnect answered with an error (a duplicate, an unknown deck, a wrong key). */
class AnkiConnectError(message: String) : Exception(message) {
    val isDuplicate: Boolean get() = "duplicate" in message.orEmpty().lowercase()
}

/**
 * AnkiConnect is JSON over HTTP: `{action, version, params, key}` in, `{result, error}` out
 * (https://foosoft.net/projects/anki-connect/). Unreachable servers throw [IOException]; the
 * server's own refusals throw [AnkiConnectError].
 */
@Singleton
class AnkiConnect @Inject constructor(okHttp: OkHttpClient) {

    private val client = okHttp.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val moshi = Moshi.Builder().build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )

    suspend fun invoke(config: AnkiConfig, action: String, params: Map<String, Any?> = emptyMap()): Any? =
        withContext(Dispatchers.IO) {
            val body = buildMap {
                put("action", action)
                put("version", 6)
                if (params.isNotEmpty()) put("params", params)
                if (config.apiKey.isNotBlank()) put("key", config.apiKey)
            }
            val request = Request.Builder()
                .url(config.url)
                .post(mapAdapter.toJson(body).toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("AnkiConnect answered HTTP ${response.code}")
                val reply = mapAdapter.fromJson(response.body.string()) ?: throw IOException("Empty reply")
                (reply["error"] as? String)?.let { throw AnkiConnectError(it) }
                reply["result"]
            }
        }

    suspend fun version(config: AnkiConfig): Int = (invoke(config, "version") as Number).toInt()

    @Suppress("UNCHECKED_CAST")
    suspend fun deckNames(config: AnkiConfig): List<String> = invoke(config, "deckNames") as List<String>

    @Suppress("UNCHECKED_CAST")
    suspend fun modelNames(config: AnkiConfig): List<String> = invoke(config, "modelNames") as List<String>

    @Suppress("UNCHECKED_CAST")
    suspend fun modelFieldNames(config: AnkiConfig, model: String): List<String> =
        invoke(config, "modelFieldNames", mapOf("modelName" to model)) as List<String>

    /** Note ids of notes in the deck whose [field] is [value]. */
    @Suppress("UNCHECKED_CAST")
    suspend fun findNotes(config: AnkiConfig, field: String, value: String): List<Long> {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        val query = "\"deck:${config.deck}\" \"$field:$escaped\""
        return (invoke(config, "findNotes", mapOf("query" to query)) as List<Number>).map { it.toLong() }
    }

    /** Note ids for an Anki search [query]. */
    @Suppress("UNCHECKED_CAST")
    suspend fun search(config: AnkiConfig, query: String): List<Long> =
        (invoke(config, "findNotes", mapOf("query" to query)) as List<Number>).map { it.toLong() }

    /** Each note's fields, by name, in its note type's order: (note id, [(field, value)]). */
    @Suppress("UNCHECKED_CAST")
    suspend fun noteFields(config: AnkiConfig, ids: List<Long>): List<Pair<Long, List<Pair<String, String>>>> {
        val notes = invoke(config, "notesInfo", mapOf("notes" to ids)) as List<Map<String, Any?>>
        return notes.mapNotNull { note ->
            val id = (note["noteId"] as? Number)?.toLong() ?: return@mapNotNull null
            val fields = (note["fields"] as? Map<String, Map<String, Any?>>).orEmpty()
                .entries.sortedBy { (it.value["order"] as? Number)?.toInt() ?: 0 }
                .map { it.key to (it.value["value"] as? String).orEmpty() }
            id to fields
        }
    }

    /** Stores a file in Anki's media folder, as [filename]; [base64] is its content. */
    suspend fun storeMediaFile(config: AnkiConfig, filename: String, base64: String) {
        invoke(config, "storeMediaFile", mapOf("filename" to filename, "data" to base64))
    }

    /** Adds a note to the configured deck; a duplicate there is refused. Returns its id. */
    suspend fun addNote(config: AnkiConfig, fields: Map<String, String>, tags: List<String>): Long {
        val note = mapOf(
            "deckName" to config.deck,
            "modelName" to config.noteType,
            "fields" to fields,
            "tags" to tags,
            "options" to mapOf("allowDuplicate" to false, "duplicateScope" to "deck"),
        )
        return (invoke(config, "addNote", mapOf("note" to note)) as Number).toLong()
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
