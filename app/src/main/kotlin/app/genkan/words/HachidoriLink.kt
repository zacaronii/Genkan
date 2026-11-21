package app.genkan.words

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.genkan.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Where the Hachidori host is: a bare host is its relay on ws://host:8771/link. */
data class HachidoriConfig(val address: String = "", val enabled: Boolean = false) {
    val url: String?
        get() {
            val a = address.trim().removeSuffix("/")
            if (a.isEmpty()) return null
            if (a.startsWith("ws://") || a.startsWith("wss://")) return if (a.endsWith("/link")) a else "$a/link"
            return if (':' in a) "ws://$a/link" else "ws://$a:$PORT/link"
        }

    private companion object {
        const val PORT = 8771
    }
}

sealed interface HachidoriStatus {
    data object Off : HachidoriStatus
    data object Connecting : HachidoriStatus
    data class Linked(val version: String, val known: Int, val ignored: Int, val waiting: Int) : HachidoriStatus
    data class Unreachable(val reason: String) : HachidoriStatus
}

private val Context.hachidoriStore: DataStore<Preferences> by preferencesDataStore(name = "hachidori")

/**
 * Genkan as a linked client of a Hachidori host (its Sharing protocol, through the Hachidori
 * relay's `/link`, as a native client: Origin `hoshi://hoshidicts`, no capabilities). Lookups and
 * mining stay on the device; what's shared is the hand marks and lookup counts:
 *
 * - On linking, the host's known and ignored words replace Genkan's, except those still waiting
 *   to reach it; later `storage` frames do the same.
 * - Each mark made here goes up with `hd_word_status_override`, one word at a time, so devices
 *   never undo each other's marks; each lookup counted here with `hd_lookup_stats_record`.
 *   Both wait on the device while the host can't be reached, as in Amatsubame's apps: the
 *   protocol has no way to send a past lookup's time, so a replayed one gets the host's clock.
 *
 * The host sends every linked client its stored AnkiConnect address and key (in `options`);
 * Genkan never reads them, and keeps its own Anki settings.
 */
@Singleton
class HachidoriLink @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttp: OkHttpClient,
    private val words: WordStatuses,
    private val counts: LookupCounts,
) {
    private val client = okHttp.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendLock = Mutex()
    private var started = false

    private val _status = MutableStateFlow<HachidoriStatus>(HachidoriStatus.Off)
    val status: StateFlow<HachidoriStatus> = _status.asStateFlow()

    val config: Flow<HachidoriConfig> = context.hachidoriStore.data.map { HachidoriConfig(it[ADDRESS].orEmpty(), it[ENABLED] == true) }

    /** Saves the address and switch; switching on queues every local mark for the host. */
    suspend fun save(config: HachidoriConfig) {
        val was = this.config.first()
        context.hachidoriStore.edit {
            it[ADDRESS] = config.address.trim()
            it[ENABLED] = config.enabled
        }
        if (config.enabled && !was.enabled) {
            words.linked = true
            counts.linked = true
            words.queueAllForHost()
        } else if (!config.enabled && was.enabled) {
            words.linked = false
            counts.linked = false
            words.dropPending()
            counts.dropPending()
        }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            config.collectLatest { config ->
                words.linked = config.enabled
                counts.linked = config.enabled
                val url = config.url
                if (!config.enabled || url == null) {
                    _status.value = HachidoriStatus.Off
                    return@collectLatest
                }
                var attempt = 0
                while (isActive) {
                    _status.value = HachidoriStatus.Connecting
                    val reason = session(url)
                    _status.value = HachidoriStatus.Unreachable(reason)
                    // Hachidori's own backoff: min(10 s, 500 ms·2^n).
                    delay(minOf(MAX_BACKOFF_MS, 500L shl minOf(attempt++, 5)))
                    if (reason == LINKED_THEN_CLOSED) attempt = 0
                }
            }
        }
    }

    /** One connection, until it closes; why it ended. */
    private suspend fun session(url: String): String {
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        val closed = CompletableDeferred<String>()
        val replies = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
        val ids = AtomicInteger()
        val request = Request.Builder().url(url).header("Origin", ORIGIN).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    JSONObject().put("kind", "hello").put("protocol", 1).put("version", BuildConfig.VERSION_NAME)
                        .put("name", "Genkan").put("capabilities", JSONArray()).toString(),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (frame.optString("kind")) {
                    "ping" -> webSocket.send("""{"kind":"pong"}""")
                    "reply" -> replies.remove(frame.optInt("id"))?.complete(frame.optJSONObject("response") ?: JSONObject())
                    // Clients ignore kinds they don't know (word-status: the host's Anki index changed).
                    "hello", "storage", "bye" -> events.trySend(frame)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(if (response?.code == 503) "no Hachidori host is connected to the relay" else t.message ?: t.javaClass.simpleName)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(reason.ifEmpty { "closed ($code)" })
            }
        })
        var linked = false
        val link = Link(socket, replies, ids)
        val work = scope.launch {
            for (frame in events) {
                when (frame.optString("kind")) {
                    "hello" -> {
                        linked = true
                        hostVersion = frame.optString("version")
                        frame.optJSONObject("snapshot")?.optJSONObject("wordStatusOverrides")?.let { adopt(it) }
                        Log.i(TAG, "Linked to Hachidori ${frame.optString("version")}")
                        flush(link)
                        launch { merge(words.changed, counts.changed).collect { flush(link) } }
                    }
                    "storage" -> frame.optJSONObject("changes")?.optJSONObject("wordStatusOverrides")?.let {
                        adopt(it)
                        report(hostVersion)
                    }
                    "bye" -> socket.close(1000, "bye")
                }
            }
        }
        val reason = closed.await()
        work.cancel()
        replies.values.forEach { it.cancel() }
        socket.cancel()
        return if (linked) LINKED_THEN_CLOSED else reason
    }

    private var hostVersion = ""

    private suspend fun adopt(overrides: JSONObject) {
        val known = overrides.optJSONArray("known").strings()
        val ignored = overrides.optJSONArray("ignored").strings()
        words.adoptHost(known, ignored)
    }

    /** Sends what waits here: marks first, then each lookup counted, one request each. */
    private suspend fun flush(link: Link) = sendLock.withLock {
        for (row in words.pending()) {
            val reply = link.request(
                "hd_word_status_override",
                JSONObject().put("headword", row.headword).put("status", row.status?.name?.lowercase() ?: JSONObject.NULL),
            ) ?: return@withLock
            if (reply.optBoolean("ok")) words.sent(row) else Log.w(TAG, "Hachidori refused ${row.headword}: ${reply.optString("error")}")
        }
        for (row in counts.pending()) {
            repeat(row.pendingHost) {
                val reply = link.request("hd_lookup_stats_record", JSONObject().put("term", row.term).put("reading", row.reading))
                // Sent, but the answer lost: Hachidori never retries those, and neither do we.
                counts.sentOne(row.term, row.reading)
                val stats = reply?.optJSONObject("statistics") ?: return@withLock
                counts.adoptHost(row.term, row.reading, stats.optInt("lookupCount"), stats.optLongOrNull("firstLookedUpAt"), stats.optLongOrNull("lastLookedUpAt"))
            }
        }
        report(hostVersion)
    }

    private suspend fun report(version: String) {
        if (version.isNotEmpty()) hostVersion = version
        val marks = words.marks.value
        _status.value = HachidoriStatus.Linked(
            hostVersion,
            marks.count { it.value == WordStatus.KNOWN },
            marks.count { it.value == WordStatus.IGNORED },
            words.pending().size + counts.pending().sumOf { it.pendingHost },
        )
    }

    /** The open connection's requests. */
    private class Link(val socket: WebSocket, val replies: ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>, val ids: AtomicInteger) {
        /** The host's response to [type], or null when the link dropped or it didn't answer in time. */
        suspend fun request(type: String, payload: JSONObject): JSONObject? {
            val id = ids.incrementAndGet()
            val answer = CompletableDeferred<JSONObject>()
            replies[id] = answer
            val message = payload.put("target", "hoshidicts-worker").put("type", type).put("requestId", id)
            if (!socket.send(JSONObject().put("kind", "request").put("id", id).put("message", message).toString())) {
                replies.remove(id)
                return null
            }
            return withTimeoutOrNull(REQUEST_TIMEOUT_MS) { runCatching { answer.await() }.getOrNull() }.also { replies.remove(id) }
        }
    }

    private companion object {
        const val TAG = "HachidoriLink"
        /** The Origin the relay accepts from native clients. */
        const val ORIGIN = "hoshi://hoshidicts"
        const val MAX_BACKOFF_MS = 10_000L
        const val REQUEST_TIMEOUT_MS = 15_000L
        const val LINKED_THEN_CLOSED = "the link closed"
        val ADDRESS = stringPreferencesKey("address")
        val ENABLED = booleanPreferencesKey("enabled")

        fun JSONArray?.strings(): List<String> = this?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    }
}
