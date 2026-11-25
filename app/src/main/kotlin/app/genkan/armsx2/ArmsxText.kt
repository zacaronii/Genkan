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
 * switch as a game starts.
 */
@Singleton
class ArmsxText @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: ReaderSession,
    private val nowPlaying: NowPlaying,
    private val ocr: OcrReader,
    private val privileged: Privileged,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private val text = MemoryText(session, ocr, TAG)
    private var client: PineClient? = null

    /** Lines handed in as if OCR had read them (debug builds). */
    internal val offered: MutableSharedFlow<String> get() = text.offered

    val status: StateFlow<MemoryTextStatus> = text.status

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
                    val port = context.armsxStore.data.first()[PORT] ?: Pine.PORT
                    text.follow { openGame(port) }
                } else {
                    text.off()
                    client?.close()
                    client = null
                }
            }
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
        val ENABLED = booleanPreferencesKey("enabled")
        val SAVED_PINE = stringPreferencesKey("saved_pine")
        val PORT = intPreferencesKey("port")
    }
}
