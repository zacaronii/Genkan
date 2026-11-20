package app.genkan.ppsspp

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.genkan.reader.HookMessage
import app.genkan.reader.NowPlaying
import app.genkan.reader.ReaderSession
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ppssppStore: DataStore<Preferences> by preferencesDataStore(name = "ppsspp")

/** What the PPSSPP text source is doing, for the reader's status line. */
sealed class PpssppStatus {
    data object Off : PpssppStatus()
    data object Waiting : PpssppStatus()
    data class NoProfile(val gameId: String) : PpssppStatus()
    data class Hooked(val gameId: String) : PpssppStatus()
}

/**
 * PSP dialogue straight from PPSSPP's memory, through its remote debugger (a WebSocket on
 * localhost): non-pausing log breakpoints at the game's text call ([PspHooks]) report where each
 * line is, and a memory read fetches it. The debugger is off in PPSSPP by default and, in
 * PPSSPP 1.20, listens on the whole network while a game runs, so Genkan turns it on only when
 * the player asks (setting), and puts PPSSPP's own values back when they turn it off.
 */
@Singleton
class PpssppText @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: ReaderSession,
    private val nowPlaying: NowPlaying,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Moshi.Builder().build()
        .adapter<Map<String, Any?>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
    private var started = false

    val enabled: Flow<Boolean> = context.ppssppStore.data.map { it[ENABLED] == true }

    private val _status = MutableStateFlow<PpssppStatus>(PpssppStatus.Off)
    val status: StateFlow<PpssppStatus> = _status.asStateFlow()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(enabled, nowPlaying.game) { on, game -> on && game.platform == PSP }.collectLatest { active ->
                if (!active) {
                    _status.value = PpssppStatus.Off
                    return@collectLatest
                }
                // PPSSPP takes a while to boot and to start its debugger; keep knocking.
                while (isActive) {
                    _status.value = PpssppStatus.Waiting
                    connect()
                    delay(RETRY_MS)
                }
            }
        }
    }

    /** Turns the debugger on in PPSSPP's settings, remembering what was there; off puts it back. */
    suspend fun setEnabled(on: Boolean) {
        val ini = iniFile()
        if (ini == null) {
            Log.w(TAG, "No ppsspp.ini found; start PPSSPP once first")
        } else if (on) {
            val text = ini.readText()
            context.ppssppStore.edit {
                if (it[SAVED_PORT] == null) {
                    it[SAVED_PORT] = Ini.get(text, GENERAL, PORT_KEY) ?: "0"
                    it[SAVED_ON_STARTUP] = Ini.get(text, GENERAL, STARTUP_KEY) ?: "False"
                }
            }
            ini.writeText(Ini.set(Ini.set(text, GENERAL, PORT_KEY, PORT.toString()), GENERAL, STARTUP_KEY, "True"))
        } else {
            val prefs = context.ppssppStore.data.first()
            var text = ini.readText()
            text = Ini.set(text, GENERAL, PORT_KEY, prefs[SAVED_PORT] ?: "0")
            text = Ini.set(text, GENERAL, STARTUP_KEY, prefs[SAVED_ON_STARTUP] ?: "False")
            ini.writeText(text)
            context.ppssppStore.edit { it.remove(SAVED_PORT); it.remove(SAVED_ON_STARTUP) }
        }
        context.ppssppStore.edit { it[ENABLED] = on }
    }

    /**
     * Puts the debugger setting back before a PSP game starts: PPSSPP writes its whole ini when
     * it exits, so a copy that was running when the setting changed would have undone it.
     */
    suspend fun beforeLaunch() {
        if (enabled.first()) setEnabled(true)
    }

    /** PPSSPP's memory stick: a shared folder it was pointed at, or its default ones. */
    private fun iniFile(): File? {
        val storage = Environment.getExternalStorageDirectory()
        return MEMSTICKS.map { File(storage, "$it/PSP/SYSTEM/ppsspp.ini") }.firstOrNull { it.isFile }
    }

    /** One debugger connection, until it closes. */
    private suspend fun connect() {
        val hooks = mutableListOf<PspHook>()
        val reads = mutableMapOf<String, PspHook>()
        val tickets = AtomicInteger()
        var gameId: String? = null

        socket().collect { (ws, event) ->
            if (event == null) {
                ws.send(json.toJson(mapOf("event" to "game.status", "ticket" to "status")))
                return@collect
            }
            when (event["event"]) {
                // Another game may be loaded into the same PPSSPP: ask again which one.
                "game.start", "game.quit" -> {
                    gameId = null
                    ws.send(json.toJson(mapOf("event" to "game.status", "ticket" to "status")))
                }
                "game.status" -> {
                    val id = (event["game"] as? Map<*, *>)?.get("id") as? String ?: return@collect
                    if (id == gameId) return@collect
                    gameId = id
                    hooks.clear()
                    hooks += PspHooks.byGameId[id].orEmpty()
                    if (hooks.isEmpty()) {
                        Log.i(TAG, "No hook profile for $id")
                        _status.value = PpssppStatus.NoProfile(id)
                        return@collect
                    }
                    // PPSSPP only checks an address its JIT already compiled when a breakpoint is
                    // newly added there, and an add sent hard on a remove can lose that race. So:
                    // remove (it may not exist), and add once PPSSPP has answered the remove.
                    hooks.forEachIndexed { i, hook ->
                        ws.send(json.toJson(mapOf("event" to "cpu.breakpoint.remove", "address" to hook.address, "ticket" to "$REMOVED$i")))
                    }
                }
                "cpu.breakpoint.remove", "error" -> {
                    val i = (event["ticket"] as? String)?.removePrefix(REMOVED)?.toIntOrNull()
                    if (i == null) {
                        if (event["event"] == "error") Log.w(TAG, "PPSSPP: ${event["message"]}")
                        return@collect
                    }
                    val hook = hooks.getOrNull(i) ?: return@collect
                    ws.send(
                        json.toJson(
                            mapOf(
                                "event" to "cpu.breakpoint.add",
                                "address" to hook.address,
                                "enabled" to false,
                                "log" to true,
                                "logFormat" to "$MARK$i {${hook.register}:x}",
                                "ticket" to "add$i",
                            ),
                        ),
                    )
                }
                "cpu.breakpoint.add" -> {
                    Log.i(TAG, "Hooked $gameId (${event["ticket"]})")
                    _status.value = PpssppStatus.Hooked(gameId ?: return@collect)
                }
                "log" -> {
                    val hit = HIT.find(event["message"] as? String ?: return@collect) ?: return@collect
                    val hook = hooks.getOrNull(hit.groupValues[1].toInt()) ?: return@collect
                    val ticket = "read${tickets.incrementAndGet()}"
                    reads[ticket] = hook
                    ws.send(
                        json.toJson(
                            mapOf("event" to "memory.read", "ticket" to ticket, "address" to hit.groupValues[2].toLong(16), "size" to READ_SIZE),
                        ),
                    )
                }
                "memory.read" -> {
                    val hook = reads.remove(event["ticket"] as? String) ?: return@collect
                    val bytes = Base64.decode(event["base64"] as? String ?: return@collect, Base64.DEFAULT)
                    val text = hook.clean(PspHooks.decode(bytes, hook.charset))?.trim()

                    if (!text.isNullOrEmpty()) session.submit(HookMessage(text))
                }
            }
        }
    }

    /** The debugger's events, a null event first when it opens; completes when it closes. */
    private fun socket() = callbackFlow<Pair<DebuggerSocket, Map<String, Any?>?>> {
        val ws = try {
            DebuggerSocket.connect("127.0.0.1", PORT, "/debugger", CONNECT_TIMEOUT_MS)
        } catch (e: java.io.IOException) {
            // Refused or slow while PPSSPP boots: the caller tries again.
            if (e !is java.net.ConnectException) Log.i(TAG, "Debugger not ready: $e")
            close()
            return@callbackFlow
        }
        trySend(ws to null)
        launch {
            try {
                while (true) {
                    val text = ws.receive() ?: break
                    val event = runCatching { json.fromJson(text) }.getOrNull() ?: continue
                    send(ws to event)
                }
            } catch (e: java.io.IOException) {
                Log.i(TAG, "Debugger connection closed: $e")
            }
            channel.close()
        }
        awaitClose { ws.close() }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val TAG = "PpssppText"
        const val PSP = "psp"
        /** Genkan's fixed debugger port; PPSSPP otherwise picks one at random. */
        const val PORT = 45099
        const val READ_SIZE = 512
        const val RETRY_MS = 3_000L
        const val CONNECT_TIMEOUT_MS = 10_000
        const val MARK = "GENKAN"
        const val REMOVED = "removed"
        val HIT = Regex("$MARK(\\d+) ([0-9a-fA-F]{1,8})")
        const val GENERAL = "General"
        const val PORT_KEY = "RemoteISOPort"
        const val STARTUP_KEY = "RemoteDebuggerOnStartup"
        /** Under shared storage: a folder chosen in PPSSPP ("ppsspp"), or PSP/ at its root. */
        val MEMSTICKS = listOf("ppsspp", "", "PPSSPP")
        val ENABLED = booleanPreferencesKey("enabled")
        val SAVED_PORT = stringPreferencesKey("saved_port")
        val SAVED_ON_STARTUP = stringPreferencesKey("saved_on_startup")
    }
}
