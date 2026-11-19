package app.genkan.stats

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.genkan.launch.LocalShelves
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Where reading sessions are sent: a mochibridge server and its API token, if it has one. */
data class ReadingLogConfig(val address: String = "", val token: String = "") {
    val configured: Boolean get() = address.isNotBlank()

    val endpoint: String
        get() {
            val base = address.trim().removeSuffix("/")
            return (if ("://" in base) base else "http://$base") + "/api/genkan/session"
        }
}

private val Context.readingLogStore: DataStore<Preferences> by preferencesDataStore(name = "reading_log")

@Singleton
class ReadingLogSettings @Inject constructor(@ApplicationContext private val context: Context) {
    val config: Flow<ReadingLogConfig> = context.readingLogStore.data.map {
        ReadingLogConfig(it[ADDRESS].orEmpty(), it[TOKEN].orEmpty())
    }

    suspend fun save(config: ReadingLogConfig) {
        context.readingLogStore.edit {
            it[ADDRESS] = config.address.trim()
            it[TOKEN] = config.token.trim()
        }
    }

    private companion object {
        val ADDRESS = stringPreferencesKey("address")
        val TOKEN = stringPreferencesKey("token")
    }
}

sealed interface ReadingLogStatus {
    data object NotSet : ReadingLogStatus
    data object UpToDate : ReadingLogStatus
    data class Sent(val sessions: Int) : ReadingLogStatus
    data class Failed(val reason: String) : ReadingLogStatus
}

/**
 * Sends reading sessions to mochibridge, which logs them into kechimochi with minutes and
 * characters (`POST /api/genkan/session`). A session is sent again whenever its numbers move,
 * and mochibridge keeps the largest it has seen, so a late or repeated send never shrinks a
 * log. A session is final once it closed or has been idle for four hours.
 */
@Singleton
class ReadingLogPusher @Inject constructor(
    private val settings: ReadingLogSettings,
    private val dao: ReadingDao,
    okHttp: OkHttpClient,
) {
    private val client = okHttp.newBuilder().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )
    private var started = false

    private val _status = MutableStateFlow<ReadingLogStatus>(ReadingLogStatus.NotSet)
    val status: StateFlow<ReadingLogStatus> = _status.asStateFlow()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                push()
                delay(PUSH_MS)
            }
        }
    }

    suspend fun push() = lock.withLock {
        val config = settings.config.first()
        if (!config.configured) {
            _status.value = ReadingLogStatus.NotSet
            return@withLock
        }
        val now = System.currentTimeMillis()
        var sent = 0
        for (session in dao.recentTotals(RECENT)) {
            if (session.characters == 0L && session.activeMs == 0L) continue
            val body = payload(session, now)
            val fingerprint = "${body["seconds"]}/${body["characters"]}/${body["final"]}"
            if (fingerprint == session.pushed) continue
            val error = send(config, body)
            if (error != null) {
                _status.value = ReadingLogStatus.Failed(error)
                return@withLock
            }
            dao.markPushed(session.id, fingerprint)
            sent++
        }
        _status.value = if (sent > 0) ReadingLogStatus.Sent(sent) else ReadingLogStatus.UpToDate
        if (sent > 0) Log.i(TAG, "Sent $sent reading sessions")
    }

    private fun payload(s: ReadingSessionTotals, now: Long): Map<String, Any?> = buildMap {
        put("session_id", "genkan-${s.id}-${s.started}")
        put("kind", if (s.platform == LocalShelves.PC_SLUG) "vn" else "game")
        put("title", s.title)
        s.rommRomId?.let { put("romm_rom_id", it) }
        s.platform?.let { put("platform", it) }
        put("started_at", Instant.ofEpochMilli(s.started).toString())
        put("seconds", s.activeMs / 1000)
        put("characters", s.characters)
        put("final", s.ended != null || now - s.lastActivity > ReadingRules.SESSION_GAP_MS)
        put("language", "ja")
        put("quality", "measured")
    }

    /** Null when it was taken, else why not. */
    private fun send(config: ReadingLogConfig, body: Map<String, Any?>): String? = try {
        val request = Request.Builder()
            .url(config.endpoint)
            .apply { if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token}") }
            .post(adapter.toJson(body).toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { if (it.isSuccessful) null else "HTTP ${it.code}" }
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    private companion object {
        const val TAG = "ReadingLogPusher"
        const val PUSH_MS = 60_000L
        /** Sessions older than the most recent ones are settled already. */
        const val RECENT = 50
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
