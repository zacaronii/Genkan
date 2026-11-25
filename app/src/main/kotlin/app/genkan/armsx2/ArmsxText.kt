package app.genkan.armsx2

import android.content.Context
import android.os.Environment
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
import app.genkan.privileged.Privileged
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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.armsxStore: DataStore<Preferences> by preferencesDataStore(name = "armsx2")

/**
 * PS2 text from ARMSX2's memory, through PINE (PineClient). Like Azahar's RPC, PINE can only read
 * memory, so the text is found and followed by [MemoryText], with OCR alongside. PINE is off in
 * ARMSX2 by default (it listens on the device only), so Genkan turns it on in ARMSX2's settings
 * file when the player asks (setting), and puts ARMSX2's own value back after. ARMSX2 reads the
 * switch as a game starts. A game the Yomikata catalog has a script for is read by that script
 * instead, run here (AppScriptHost) on a clock of its own ([PineTicks]).
 */
@Singleton
class ArmsxText @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: ReaderSession,
    private val nowPlaying: NowPlaying,
    private val ocr: OcrReader,
    private val privileged: Privileged,
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

    val enabled: Flow<Boolean> = context.armsxStore.data.map { it[ENABLED] == true }

    /** Turns PINE on in ARMSX2's settings, remembering what was there; off puts it back. */
    suspend fun setEnabled(on: Boolean) = withContext(Dispatchers.IO) {
        val saved = context.armsxStore.data.first()[SAVED_PINE]
        val edited = editSettings { text ->
            if (on) {
                val port = ArmsxIni.port(text)
                context.armsxStore.edit {
                    if (it[SAVED_PINE] == null) it[SAVED_PINE] = ArmsxIni.pineValue(text)
                    it[PORT] = port
                }
                ArmsxIni.withPine(text)
            } else {
                ArmsxIni.restored(text, saved.orEmpty())
            }
        }
        if (!edited) Log.w(TAG, "No ARMSX2 settings changed: start ARMSX2 once first (and Shizuku, for its own folder)")
        else if (!on) context.armsxStore.edit { it.remove(SAVED_PINE) }
        context.armsxStore.edit { it[ENABLED] = on }
    }

    /** Puts the setting back before a PS2 game starts, in case ARMSX2 rewrote its settings. */
    suspend fun beforeLaunch() {
        if (enabled.first()) setEnabled(true)
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(enabled, nowPlaying.game) { on, game -> on && game.platform == PS2 }.collectLatest { active ->
                if (active) {
                    follow(context.armsxStore.data.first()[PORT] ?: Pine.PORT)
                } else {
                    text.off()
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
    private suspend fun follow(port: Int) {
        val client = client ?: PineClient(port).also { client = it }
        while (true) {
            val game = withContext(Dispatchers.IO) { client.takeIf { it.status() == PineStatus.RUNNING }?.game() }
            if (game == null) {
                kotlinx.coroutines.delay(WAIT_MS)
                continue
            }
            if (runScript(client, game)) continue
            text.follow { openGame(port) }
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
    private fun openGame(port: Int): MemorySource? {
        val client = client ?: PineClient(port).also { client = it }
        if (client.status() != PineStatus.RUNNING) return null
        return PineMemory(client)
    }

    /**
     * Rewrites ARMSX2's settings file with [change]: in a folder under shared storage it was set
     * up with, else in its own Android/data folder through Shizuku. False when there is none yet
     * (ARMSX2 writes it when it first starts) or it can't be reached.
     */
    private suspend fun editSettings(change: suspend (String) -> String): Boolean {
        val storage = Environment.getExternalStorageDirectory()
        sharedSettings(storage)?.let { file ->
            file.writeText(change(file.readText()))
            Log.i(TAG, "Changed ${file.path}")
            return true
        }
        val helper = privileged.files() ?: return false
        val own = "${storage.path}/${ArmsxIni.OWN_FOLDER}"
        if (helper.list(own).none { it.substringBefore('\t') == ArmsxIni.FILE }) return false
        val stage = File(storage, "Genkan/.staging/armsx2").apply { mkdirs() }
        try {
            val copy = File(stage, ArmsxIni.FILE)
            if (!helper.copy("$own/${ArmsxIni.FILE}", copy.path)) return false
            copy.writeText(change(copy.readText()))
            return helper.copy(copy.path, "$own/${ArmsxIni.FILE}").also { if (it) Log.i(TAG, "Changed $own/${ArmsxIni.FILE}") }
        } finally {
            stage.deleteRecursively()
        }
    }

    /** ARMSX2's settings in a folder under shared storage (its setup wizard's choice), at most two folders down. */
    private fun sharedSettings(storage: File): File? {
        fun at(dir: File) = File(dir, ArmsxIni.FILE).takeIf { it.isFile }
        val top = storage.listFiles { f -> f.isDirectory && f.name != "Android" && !f.name.startsWith(".") }.orEmpty()
        return top.firstNotNullOfOrNull(::at)
            ?: top.asSequence().flatMap { it.listFiles { f -> f.isDirectory }.orEmpty().asSequence() }.firstNotNullOfOrNull(::at)
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
        val SAVED_PINE = stringPreferencesKey("saved_pine")
        val PORT = intPreferencesKey("port")
    }
}
