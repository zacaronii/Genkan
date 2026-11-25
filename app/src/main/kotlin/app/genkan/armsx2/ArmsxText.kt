package app.genkan.armsx2

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.genkan.memory.MemorySource
import app.genkan.memory.MemoryText
import app.genkan.memory.MemoryTextStatus
import app.genkan.ocr.OcrReader
import app.genkan.reader.NowPlaying
import app.genkan.reader.ReaderSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.armsxStore: DataStore<Preferences> by preferencesDataStore(name = "armsx2")

/**
 * PS2 text from ARMSX2's memory, through PINE (PineClient). Like Azahar's RPC, PINE can only read
 * memory, so the text is found and followed by [MemoryText], with OCR alongside. PINE is off in
 * ARMSX2 by default (it listens on the device only) and is switched on in ARMSX2 itself, which
 * keeps its settings to itself and rewrites its settings file as it starts: Genkan only says
 * whether it answers ([pine]). A game the Yomikata catalog has a script for is read by that script
 * instead, run here (AppScriptHost) on a clock of its own ([PineTicks]).
 */
@Singleton
class ArmsxText @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: ReaderSession,
    private val nowPlaying: NowPlaying,
    private val ocr: OcrReader,
    private val catalog: app.genkan.yomikata.YomikataCatalog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private val text = MemoryText(session, ocr, TAG)
    private var client: PineClient? = null

    /** Lines handed in as if OCR had read them (debug builds). */
    internal val offered: MutableSharedFlow<String> get() = text.offered

    val status: StateFlow<MemoryTextStatus> = text.status

    private val _scriptActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** The game's text comes from its Yomikata script. */
    val scriptActive: StateFlow<Boolean> = _scriptActive

    private val _pine = kotlinx.coroutines.flow.MutableStateFlow(ArmsxPine.UNKNOWN)
    /** Whether ARMSX2's PINE answers while a PS2 game is followed. */
    val pine: StateFlow<ArmsxPine> = _pine

    val enabled: Flow<Boolean> = context.armsxStore.data.map { it[ENABLED] == true }

    suspend fun setEnabled(on: Boolean) {
        context.armsxStore.edit {
            it[ENABLED] = on
            // Left by builds that edited ARMSX2's settings file.
            it.remove(OLD_SAVED_PINE)
            it.remove(OLD_PORT)
        }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(enabled, nowPlaying.game) { on, game -> on && game.platform == PS2 }.collectLatest { active ->
                if (active) {
                    follow()
                } else {
                    text.off()
                    _pine.value = ArmsxPine.UNKNOWN
                    client?.close()
                    client = null
                }
            }
        }
    }

    /**
     * Waits for ARMSX2 to run a game, then reads it by its script while the catalog has one it can
     * run (again for each game that follows), else follows its text through [MemoryText].
     */
    private suspend fun follow() {
        val client = client ?: PineClient().also { client = it }
        var misses = 0
        while (true) {
            val game = withContext(Dispatchers.IO) {
                val status = client.status()
                misses = if (status == null) misses + 1 else 0
                _pine.value = ArmsxPine.of(_pine.value, status != null, client.lastFailure, misses)
                // PINE serves one client at a time: with no game to read, the connection is let go.
                if (status == PineStatus.RUNNING) client.game() else null.also { client.close() }
            }
            if (game == null) {
                kotlinx.coroutines.delay(WAIT_MS)
                continue
            }
            if (runScript(client, game)) continue
            text.follow { openGame() }
            return
        }
    }

    /**
     * Runs the catalog's script for [game] until the game stops or another starts: true then, false
     * when it has none this Genkan can run, or it died (the text is then followed as without one).
     */
    private suspend fun runScript(client: PineClient, game: PineGame): Boolean {
        val id = game.serial
        val files = when (val found = runCatching { catalog.script(id) }.getOrElse { app.genkan.yomikata.ScriptLookup.Failed(it.message ?: "") }) {
            is app.genkan.yomikata.ScriptLookup.Found -> found.files
            app.genkan.yomikata.ScriptLookup.NotListed -> return false
            is app.genkan.yomikata.ScriptLookup.Failed -> return false.also { Log.w(TAG, "${game.title} ($id): no script, ${found.reason}") }
        }
        val script = files.script
        script.unusable(app.genkan.script.AppScriptHost.level(), game.buildId, emptySet())?.let { why ->
            Log.i(TAG, "${game.title} ($id): its script can't run here: $why")
            return false
        }
        val startedMs = System.currentTimeMillis()
        while (true) {
            val error = withContext(Dispatchers.IO) {
                app.genkan.script.AppScriptHost.load(
                    script.path.substringAfterLast('/'), files.source, files.data, PS2, id, game.buildId, POINTER_MASK, 0L, PineMemory(client),
                )
            } ?: break
            if (!app.genkan.core.ScriptRetry.again(error, System.currentTimeMillis(), startedMs, null)) {
                Log.i(TAG, "${game.title} ($id): its script didn't load: $error")
                return false
            }
            kotlinx.coroutines.delay(app.genkan.core.ScriptRetry.EVERY_MS)
        }
        Log.i(TAG, "${game.title} ($id, ${game.buildId}): reading its Yomikata script")
        val reader = app.genkan.core.ScriptReader()
        val ticks = PineTicks(System.currentTimeMillis())
        var sinceCheck = 0
        _scriptActive.value = true
        try {
            while (true) {
                val now = System.currentTimeMillis()
                when (withContext(Dispatchers.IO) { ticks.step(client.status(), now) }) {
                    PineTicks.Step.STOP -> return true
                    PineTicks.Step.WAIT -> Unit
                    PineTicks.Step.STATE_LOADED -> {
                        app.genkan.script.AppScriptHost.stateLoaded()
                        reader.stateLoaded()
                    }
                    PineTicks.Step.FRAME -> {
                        val events = withContext(Dispatchers.IO) { app.genkan.script.AppScriptHost.frame(ticks.frame, PineMemory(client)) }
                        events.filter { it.first == app.genkan.core.ScriptEvents.LOG }.forEach { Log.i(TAG, "Script log: ${it.second}") }
                        val step = reader.read(events, now)
                        step.lines.forEach(session::submit)
                        if (step.dead) {
                            Log.w(TAG, "The script was switched off: following the text in memory from here")
                            return false
                        }
                    }
                }
                // OCR stays on for the screens the script doesn't read, out of the backlog while its windows are up.
                ocr.quiet = reader.windowOpen(now)
                // Another game started (a disc changed, or a new one launched): its own script, if any.
                if (++sinceCheck >= GAME_CHECK_TICKS) {
                    sinceCheck = 0
                    if (withContext(Dispatchers.IO) { client.game() }?.serial != game.serial) return true
                }
                kotlinx.coroutines.delay(TICK_MS)
            }
        } finally {
            app.genkan.script.AppScriptHost.unload()
            _scriptActive.value = false
            ocr.quiet = false
        }
    }

    /** The game's memory through PINE, while ARMSX2 is running one; each open is a fresh tick. */
    private fun openGame(): MemorySource? {
        val client = client ?: PineClient().also { client = it }
        if (client.status() != PineStatus.RUNNING) {
            client.close()
            return null
        }
        return PineMemory(client)
    }

    private companion object {
        const val TAG = "ArmsxText"
        const val PS2 = "ps2"
        /** The EE sees its 32 MB through several segments: a script's pointer folds back to it. */
        const val POINTER_MASK = 0x01FFFFFFL
        /** A script's tick: ARMSX2's memory read a page at a time each, ten times a second. */
        const val TICK_MS = 100L
        /** How often the game is checked for having changed, in ticks. */
        const val GAME_CHECK_TICKS = 50
        /** How often to look for a game in ARMSX2 while it runs none. */
        const val WAIT_MS = 2_000L
        val ENABLED = booleanPreferencesKey("enabled")
        val OLD_SAVED_PINE = stringPreferencesKey("saved_pine")
        val OLD_PORT = intPreferencesKey("port")
    }
}

/** Whether ARMSX2's PINE answers: not known yet, it does, it's switched off, or another client has it. */
enum class ArmsxPine {
    UNKNOWN, ANSWERS, OFF, BUSY;

    companion object {
        /** Polls without an answer before saying so: ARMSX2 takes a moment to start listening. */
        const val GRACE = 2

        /**
         * The state after [current], from a poll that [answered] or not, the last [failure], and
         * [misses] polls in a row without an answer.
         */
        fun of(current: ArmsxPine, answered: Boolean, failure: PineFailure?, misses: Int): ArmsxPine = when {
            answered -> ANSWERS
            misses < GRACE -> current
            failure == PineFailure.SILENT -> BUSY
            else -> OFF
        }
    }
}
