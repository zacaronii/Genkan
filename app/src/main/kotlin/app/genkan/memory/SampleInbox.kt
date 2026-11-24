package app.genkan.memory

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.genkan.romm.KeystoreTokenCipher
import app.genkan.romm.TokenCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A Yomikata sample inbox (`yomikata serve`) and the token it expects. */
data class InboxConfig(val address: String = "", val token: String = "") {
    val configured: Boolean get() = address.isNotBlank() && token.isNotBlank()

    /** A bare host gets https; a full URL is used as written. */
    val endpoint: String get() = endpoint("samples")

    /** Where RAM snapshots go. */
    val snapshotsEndpoint: String get() = endpoint("snapshots")

    /** Where hook reports go (docs/hook-finder.md). */
    val hooksEndpoint: String get() = endpoint("hooks")

    private fun endpoint(kind: String): String {
        val base = address.trim().removeSuffix("/")
        return (if ("://" in base) base else "https://$base") + "/api/$kind"
    }
}

sealed interface InboxStatus {
    data object NotSet : InboxStatus
    data object Idle : InboxStatus
    data class Sent(val files: Int, val added: Int, val snapshots: Int = 0) : InboxStatus
    data class Unreachable(val reason: String) : InboxStatus
    data class Refused(val reason: String) : InboxStatus
}

private val Context.yomikataStore: DataStore<Preferences> by preferencesDataStore(name = "yomikata")

/**
 * Sends the memory sample files ([TextSamples]) to a Yomikata inbox, so they needn't be copied
 * off the device by hand. A file goes whole, as written, whenever it has more samples than last
 * sent (the inbox skips the ones it has); one that can't be sent waits for the next try. The
 * files stay on the device either way. The token is sealed with a Keystore key, like RomM's.
 */
@Singleton
class SampleInbox @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttp: OkHttpClient,
    private val samples: TextSamples,
    private val snapshots: RamSnapshots,
) {
    private val client = okHttp.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val cipher: TokenCipher = KeystoreTokenCipher("genkan_yomikata_token")
    private val lock = Mutex()

    private val _status = MutableStateFlow<InboxStatus>(InboxStatus.Idle)
    val status: StateFlow<InboxStatus> = _status.asStateFlow()

    val config: Flow<InboxConfig> = context.yomikataStore.data.map {
        InboxConfig(it[ADDRESS].orEmpty(), it[TOKEN]?.let(cipher::decrypt).orEmpty())
    }

    suspend fun save(config: InboxConfig) {
        context.yomikataStore.edit {
            it[ADDRESS] = config.address.trim()
            if (config.token.isBlank()) it.remove(TOKEN) else it[TOKEN] = cipher.encrypt(config.token.trim())
        }
    }

    /** The sample files on this device and how many samples each holds. */
    suspend fun local(): Map<File, Int> = withContext(Dispatchers.IO) {
        samples.dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .associateWith { f -> runCatching { JSONObject(f.readText()).getJSONArray("samples").length() }.getOrDefault(0) }
            .filterValues { it > 0 }
    }

    /**
     * Sends every file that grew since it was last sent; [all] sends them all again. Snapshots
     * held for an inbox limit go only when [retryHeld] (the Yomikata page's send).
     */
    suspend fun send(all: Boolean = false, retryHeld: Boolean = false) = lock.withLock {
        val config = config.first()
        if (!config.configured) {
            _status.value = InboxStatus.NotSet
            return@withLock
        }
        val sent = context.yomikataStore.data.first()
        var files = 0
        var added = 0
        var refused = false
        for ((file, count) in local()) {
            val key = sentKey(file)
            if (!all && (sent[key] ?: 0) >= count) continue
            val answer = try {
                post(config, file)
            } catch (e: IOException) {
                _status.value = InboxStatus.Unreachable(e.message ?: e.javaClass.simpleName)
                return@withLock
            }
            when (answer.code) {
                200 -> {
                    files++
                    added += runCatching { JSONObject(answer.body).optInt("added") }.getOrDefault(0)
                    context.yomikataStore.edit { it[key] = count }
                }
                401 -> {
                    _status.value = InboxStatus.Refused("the inbox didn't accept the token")
                    return@withLock
                }
                else -> {
                    // A file the inbox refuses (too large, malformed) is left for the next try; the others go.
                    val detail = runCatching { JSONObject(answer.body).optString("detail") }.getOrNull()
                    _status.value = InboxStatus.Refused("${file.name}: HTTP ${answer.code}${detail?.let { " $it" }.orEmpty()}")
                    Log.i(TAG, "Inbox refused ${file.name}: ${answer.code} $detail")
                    refused = true
                }
            }
        }
        val sentSnapshots = try {
            sendSnapshots(config, retryHeld)
        } catch (e: IOException) {
            _status.value = InboxStatus.Unreachable(e.message ?: e.javaClass.simpleName)
            return@withLock
        } ?: return@withLock
        if (!refused && !snapshotHeld) _status.value = if (files > 0 || sentSnapshots > 0) InboxStatus.Sent(files, added, sentSnapshots) else InboxStatus.Idle
        if (files > 0) Log.i(TAG, "Sent $files sample files to the inbox, $added new samples")
    }

    /** A snapshot the inbox refused waits on the device (see [keepsRefused]); its reason is the status. */
    private var snapshotHeld = false

    /**
     * The snapshots the inbox refused since Genkan started, by name, with its reason:
     * the automatic send skips them, so tens of megabytes don't go over the network on every
     * Home; the Yomikata page's send tries them again, and so does a restart.
     */
    private val held = mutableMapOf<String, String>()

    /**
     * Sends the RAM snapshots waiting on the device, each removed once the inbox has it (or
     * refuses it for good); null when the inbox refused the token.
     */
    private suspend fun sendSnapshots(config: InboxConfig, retryHeld: Boolean): Int? = withContext(Dispatchers.IO) {
        snapshotHeld = false
        var sent = 0
        val pending = snapshots.pending()
        // An inbox that stores only RAM would take a snapshot and drop its state and screen, and
        // the same RAM sent again later is a duplicate: those wait until the inbox keeps them.
        val keepsExtras = pending.none(snapshots::hasExtras) || keepsExtras(config)
        for (meta in pending) {
            val ram = snapshots.ramOf(meta)
            if (!ram.isFile) {
                snapshots.remove(meta)
                continue
            }
            if (!keepsExtras && snapshots.hasExtras(meta)) continue
            if (skipsHeld(meta.name, held.keys, retryHeld)) {
                _status.value = InboxStatus.Refused("${meta.name} kept on this device: ${held[meta.name]}")
                snapshotHeld = true
                continue
            }
            val parts = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("meta", null, meta.readBytes().toRequestBody(JSON))
                .addFormDataPart("ram", ram.name, ram.asRequestBody(GZIP))
            // Video RAM, when it was taken: an inbox that doesn't keep it yet ignores the part.
            snapshots.vramOf(meta).takeIf { it.isFile }?.let { parts.addFormDataPart("vram", it.name, it.asRequestBody(GZIP)) }
            snapshots.stateOf(meta).takeIf { it.isFile }?.let { parts.addFormDataPart("state", it.name, it.asRequestBody(GZIP)) }
            snapshots.screenOf(meta).takeIf { it.isFile }?.let { parts.addFormDataPart("screen", it.name, it.asRequestBody(PNG)) }
            val body = parts.build()
            val request = Request.Builder()
                .url(config.snapshotsEndpoint)
                .header("Authorization", "Bearer ${config.token.trim()}")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                when (response.code) {
                    200 -> {
                        sent++
                        held.remove(meta.name)
                        snapshots.remove(meta)
                        Log.i(TAG, "Snapshot ${meta.name}: $text")
                    }
                    401 -> {
                        _status.value = InboxStatus.Refused("the inbox didn't accept the token")
                        return@withContext null
                    }
                    in REFUSED -> {
                        // Kept, whatever the reason: a capture can't be taken again, and the inbox
                        // can change (a raised limit, a field it now takes); the reason shows until it goes.
                        Log.w(TAG, "Inbox can't take snapshot ${meta.name} yet: ${response.code} $text")
                        held[meta.name] = detailOf(text)
                        _status.value = InboxStatus.Refused("${meta.name} kept on this device: ${detailOf(text)}")
                        snapshotHeld = true
                    }
                    else -> Log.i(TAG, "Snapshot ${meta.name} not sent yet: HTTP ${response.code}")
                }
            }
        }
        if (sent > 0) Log.i(TAG, "Sent $sent RAM snapshots to the inbox")
        if (!keepsExtras) Log.i(TAG, "Snapshots with state or screen wait until the inbox keeps them")
        sent
    }

    /** Whether the inbox keeps a snapshot's optional parts: its listing then counts them per game. */
    private fun keepsExtras(config: InboxConfig): Boolean {
        val request = Request.Builder().url(config.snapshotsEndpoint).header("Authorization", "Bearer ${config.token.trim()}").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val games = runCatching { JSONObject(response.body.string()).getJSONArray("games") }.getOrNull() ?: return@use false
            // An empty inbox can't say; its answer to the first upload lists what it kept.
            games.length() == 0 || (0 until games.length()).any { games.getJSONObject(it).has("with") }
        }
    }

    /** How many games the inbox holds samples for; throws when it can't be reached or refuses. */
    suspend fun check(config: InboxConfig): Int = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(config.endpoint).header("Authorization", "Bearer ${config.token.trim()}").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw IOException("the inbox didn't accept the token")
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            JSONObject(response.body.string()).optJSONArray("games")?.length() ?: 0
        }
    }

    /**
     * Sends one hook report; says what the inbox did, for the finder's message. A report for the
     * same address, register and disc version replaces the inbox's earlier one.
     */
    suspend fun sendHook(report: String): String = withContext(Dispatchers.IO) {
        val config = config.first()
        if (!config.configured) return@withContext "kept on this device (no Yomikata inbox set)"
        val request = Request.Builder()
            .url(config.hooksEndpoint)
            .header("Authorization", "Bearer ${config.token.trim()}")
            .post(report.toRequestBody(JSON))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                when (response.code) {
                    200 -> if (runCatching { JSONObject(body).optBoolean("replaced") }.getOrDefault(false)) {
                        "sent to Yomikata (replacing the earlier report)"
                    } else {
                        "sent to Yomikata"
                    }
                    401 -> "kept on this device: the inbox didn't accept the token"
                    else -> "kept on this device: the inbox refused it (HTTP ${response.code} ${runCatching { JSONObject(body).optString("detail") }.getOrNull().orEmpty()})".trimEnd()
                }.also { Log.i(TAG, "Hook report: ${response.code} $body") }
            }
        } catch (e: IOException) {
            "kept on this device: the inbox couldn't be reached (${e.message ?: e.javaClass.simpleName})"
        }
    }

    private class Answer(val code: Int, val body: String)

    private suspend fun post(config: InboxConfig, file: File): Answer = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.endpoint)
            .header("Authorization", "Bearer ${config.token.trim()}")
            .post(body(file).toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { Answer(it.code, it.body.string()) }
    }

    /** A sample file as sent: the platform in Yomikata's names, for files written before Genkan used them. */
    private fun body(file: File): String {
        val doc = JSONObject(file.readText())
        doc.optString("platform").takeIf { it.isNotEmpty() }?.let { doc.put("platform", TextSamples.yomikataPlatform(it)) }
        return doc.toString()
    }

    private fun sentKey(file: File) = intPreferencesKey("sent:${file.name}")

    companion object {
        /**
         * Whether a snapshot the inbox answered with [code] stays on the device, held: any refusal
         * of what it is (400) or how large (413), since the inbox may take it later and the
         * moment can't be captured again. Other answers leave it to be sent as usual.
         */
        fun keepsRefused(code: Int): Boolean = code in REFUSED

        private val REFUSED = setOf(400, 413)

        /** Whether the automatic send skips [name]: it's held for an inbox limit and not retried now. */
        fun skipsHeld(name: String, held: Set<String>, retryHeld: Boolean): Boolean = !retryHeld && name in held

        /** The inbox's reason (its JSON "detail"), else the body as it came. */
        fun detailOf(body: String): String =
            runCatching { JSONObject(body).optString("detail") }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)


        private const val TAG = "SampleInbox"
        private val ADDRESS = stringPreferencesKey("inbox_address")
        private val TOKEN = stringPreferencesKey("inbox_token")
        private val JSON = "application/json".toMediaType()
        private val GZIP = "application/gzip".toMediaType()
        private val PNG = "image/png".toMediaType()
    }
}
