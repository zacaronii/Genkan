package app.genkan.azahar

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.genkan.memory.FoundText
import app.genkan.memory.MemorySource
import app.genkan.memory.MemoryText
import app.genkan.memory.MemoryTextStatus
import app.genkan.ocr.OcrReader
import app.genkan.ppsspp.Ini
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

private val Context.azaharStore: DataStore<Preferences> by preferencesDataStore(name = "azahar")

/**
 * 3DS text from Azahar's memory, through its scripting RPC (UDP on port 45987). The RPC can
 * only read memory, so the text is found and followed by [MemoryText]. The RPC is off in Azahar by default and listens on the whole network while a game runs, so Genkan
 * turns it on only when the player asks (setting), and puts Azahar's own value back after.
 */
@Singleton
class AzaharText @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: ReaderSession,
    private val nowPlaying: NowPlaying,
    private val ocr: OcrReader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private val text = MemoryText(session, ocr, TAG)

    /** Lines handed in as if OCR had read them (debug builds). */
    internal val offered: MutableSharedFlow<String> get() = text.offered

    val status: StateFlow<MemoryTextStatus> = text.status

    val enabled: Flow<Boolean> = context.azaharStore.data.map { it[ENABLED] == true }

    /** Turns the RPC on in Azahar's settings, remembering what was there; off puts it back. */
    suspend fun setEnabled(on: Boolean) = withContext(Dispatchers.IO) {
        val ini = configFile()
        if (ini == null) {
            Log.w(TAG, "No Azahar config.ini found; start Azahar once first")
        } else if (on) {
            val text = ini.readText()
            context.azaharStore.edit {
                if (it[SAVED_RPC] == null) it[SAVED_RPC] = Ini.get(text, DEBUGGING, RPC_KEY).orEmpty()
            }
            ini.writeText(Ini.set(text, DEBUGGING, RPC_KEY, "1"))
        } else {
            val saved = context.azaharStore.data.first()[SAVED_RPC].orEmpty()
            ini.writeText(Ini.set(ini.readText(), DEBUGGING, RPC_KEY, saved))
            context.azaharStore.edit { it.remove(SAVED_RPC) }
        }
        context.azaharStore.edit { it[ENABLED] = on }
    }

    /** Puts the setting back before a 3DS game starts, in case Azahar rewrote its config. */
    suspend fun beforeLaunch() {
        if (enabled.first()) setEnabled(true)
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(enabled, nowPlaying.game) { on, game -> on && game.platform == THREE_DS }.collectLatest { active ->
                if (active) text.follow(::openGame) else text.off()
            }
        }
    }

    /** The RPC, pointed at the game's process (not the system modules running beside it). */
    private fun openGame(): MemorySource? {
        val rpc = AzaharRpc()
        val game = rpc.processes().firstOrNull { it.isGame }
        if (game == null || !rpc.select(game.pid)) {
            rpc.close()
            return null
        }
        return AzaharMemory(rpc)
    }

    /** For the debug receiver: pointers into [targets]. */
    suspend fun pointersNow(targets: LongRange): List<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        openGame()?.use { text.pointers(it, targets) }.orEmpty()
    }

    /** For the debug receiver: aligned words holding a value in [values], wherever they point. */
    suspend fun valuesNow(values: LongRange): List<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        openGame()?.use { memory ->
            text.pointers(object : MemorySource by memory {
                override fun target(value: Long): Long = value
            }, values)
        }.orEmpty()
    }

    /** For the debug receiver: what a search for [line] finds, without locking on. */
    suspend fun searchNow(line: String): List<FoundText> = withContext(Dispatchers.IO) {
        openGame()?.use { text.search(it, line) }.orEmpty()
    }

    /** Azahar's settings: a folder it was pointed at under shared storage. */
    private fun configFile(): File? {
        val storage = Environment.getExternalStorageDirectory()
        return CONFIG_PATHS.map { File(storage, it) }.firstOrNull { it.isFile }
    }

    private companion object {
        const val TAG = "AzaharText"
        const val DEBUGGING = "Debugging"
        const val RPC_KEY = "enable_rpc_server"
        const val THREE_DS = "3ds"
        val CONFIG_PATHS = listOf("Azahar/config/config.ini", "azahar/config/config.ini")
        val ENABLED = booleanPreferencesKey("enabled")
        val SAVED_RPC = stringPreferencesKey("saved_rpc")
    }
}

/** A 3DS game's memory through Azahar's RPC. */
private class AzaharMemory(private val rpc: AzaharRpc) : MemorySource {
    override val regions: List<LongRange> = REGIONS
    override val maxRead: Int = AzaharRpc.MAX_READ
    override fun target(value: Long): Long? = value.takeIf { v -> REGIONS.any { v in it } }
    override fun read(address: Long, size: Int): ByteArray? = rpc.read(address, size)
    override fun close() = rpc.close()

    private companion object {
        /**
         * The game's data areas: its heap, and the linear heap at both the old address and the one
         * newer system versions map it at. Unmapped stretches read back as zeros.
         */
        val REGIONS: List<LongRange> = listOf(
            0x08000000L until 0x10000000L,
            0x14000000L until 0x1C000000L,
            0x30000000L until 0x40000000L,
        )
    }
}
