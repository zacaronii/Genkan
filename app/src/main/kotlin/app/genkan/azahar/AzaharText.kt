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
import app.genkan.ppsspp.Ini
import app.genkan.ppsspp.PspHooks
import app.genkan.ocr.OcrReader
import app.genkan.reader.HookMessage
import app.genkan.reader.NowPlaying
import app.genkan.reader.ReaderSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.azaharStore: DataStore<Preferences> by preferencesDataStore(name = "azahar")

/** A string found in memory and what it says now. */
data class FoundText(val hit: TextHit, val start: Long, val text: String)

/** What the 3DS text source is doing, for the reader's status line. */
enum class AzaharStatus { Off, NeedsOcr, Searching, NotFound, Locked }

/**
 * 3DS text from Azahar's memory, through its scripting RPC (UDP on port 45987). The RPC can
 * only read memory, so there is no text routine to watch: a line OCR read once is looked up in
 * the game's memory, and the string holding it (or the pointer to it) is then read again as the
 * game moves on, with OCR switched off. The
 * RPC is off in Azahar by default and listens on the whole network while a game runs, so Genkan
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

    /** Lines handed in as if OCR had read them (debug builds). */
    internal val offered = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val _status = MutableStateFlow(AzaharStatus.Off)
    val status: StateFlow<AzaharStatus> = _status.asStateFlow()

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
                if (!active) {
                    _status.value = AzaharStatus.Off
                    return@collectLatest
                }
                _status.value = AzaharStatus.NeedsOcr
                coroutineScope {
                    var watching: Job? = null
                    var lastTry = 0L
                    merge(ocr.lines, offered).collect { line ->
                        if (watching?.isActive == true) return@collect
                        // A scan reads the whole game's memory: not for every line OCR reads.
                        if (System.currentTimeMillis() - lastTry < RETRY_MS) return@collect
                        lastTry = System.currentTimeMillis()
                        _status.value = AzaharStatus.Searching
                        val lock = runCatching { lockOn(line) }.onFailure { Log.w(TAG, "Search failed: $it") }.getOrNull()
                        if (lock == null) {
                            _status.value = AzaharStatus.NotFound
                            return@collect
                        }
                        Log.i(TAG, "Locked on 0x%08x (%s), %d pointers to it".format(lock.found.start, lock.found.hit.charset.name(), lock.pointers.size))
                        // Memory now gives the lines; OCR would only repeat them.
                        ocr.setEnabled(false)
                        _status.value = AzaharStatus.Locked
                        watching = launch {
                            watch(lock)
                            _status.value = AzaharStatus.NeedsOcr
                        }
                    }
                }
            }
        }
    }

    /** The string OCR's [line] is in, and the pointers the game keeps to it. */
    private suspend fun lockOn(line: String): Lock? = withContext(Dispatchers.IO) {
        AzaharRpc().use { rpc ->
            if (!selectGame(rpc)) return@use null
            val candidate = search(rpc, line).firstOrNull { MemoryScan.matches(it.text, line) } ?: return@use null
            // Pointers to anywhere between the guessed start and the hit: the game's own pointer
            // also says where the string really starts.
            val pointers = mutableListOf<Pair<Long, Long>>()
            scan(rpc, overlap = 0) { chunk, base -> pointers += MemoryScan.pointersInto(chunk, base, candidate.start..candidate.hit.address) }
            val start = pointers.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.key ?: candidate.start
            val found = if (start == candidate.start) candidate else {
                val bytes = rpc.read(start, LINE_BYTES) ?: return@use null
                candidate.copy(start = start, text = PspHooks.decode(bytes, candidate.hit.charset))
            }
            Lock(found, pointers.filter { it.second == start }.map { it.first }.take(MAX_POINTERS))
        }
    }

    /**
     * Reads the locked string again and again. A game that writes each line into the same
     * buffer changes the string; one that points at lines in a table moves a pointer to it
     * instead, so a moved pointer moves the watch. A line is passed on once it reads the same
     * twice (the game may still be writing it).
     */
    private suspend fun watch(lock: Lock) = withContext(Dispatchers.IO) {
        AzaharRpc().use { rpc ->
            if (!selectGame(rpc)) return@use
            val charset = lock.found.hit.charset
            var current = lock.found.start
            val pointers = lock.pointers.associateWith { current }.toMutableMap()
            var lastSent = MemoryScan.clean(lock.found.text)
            var previous = lastSent
            var misses = 0
            while (isActive) {
                delay(POLL_MS)
                for ((slot, last) in pointers) {
                    val value = rpc.read(slot, 4)?.let(MemoryScan::u32) ?: continue
                    if (value != last) {
                        pointers[slot] = value
                        if (MemoryScan.inRegions(value)) current = value
                    }
                }
                val bytes = rpc.read(current, LINE_BYTES)
                if (bytes == null) {
                    // Three silent reads: the game or Azahar closed.
                    if (++misses >= 3) return@use
                    continue
                }
                misses = 0
                val text = MemoryScan.clean(PspHooks.decode(bytes, charset))
                if (text == previous && text != lastSent && text.isNotEmpty()) {
                    session.submit(HookMessage(text))
                    lastSent = text
                }
                previous = text
            }
        }
    }

    /** Points the RPC at the game's process (not the system modules running beside it). */
    private fun selectGame(rpc: AzaharRpc): Boolean {
        val game = rpc.processes().firstOrNull { it.isGame } ?: return false
        return rpc.select(game.pid)
    }

    /**
     * Strings in the running game's memory that look like [line]: every place a few of its
     * characters occur, widened to the whole string there, best match first.
     */
    internal fun search(rpc: AzaharRpc, line: String): List<FoundText> {
        val needles = MemoryScan.CHARSETS.flatMap { charset ->
            MemoryScan.probes(line).map { probe -> charset to probe.toByteArray(charset) }
        }
        val hits = linkedSetOf<TextHit>()
        // Even, so offsets in the window keep the parity of the addresses UTF-16 hits align to.
        val overlap = (needles.maxOf { it.second.size } - 1 + 1) and 1.inv()
        scan(rpc, overlap) { window, base ->
            for ((charset, needle) in needles) {
                for (offset in MemoryScan.find(window, needle, aligned = charset == Charsets.UTF_16LE)) {
                    hits += TextHit(base + offset, charset)
                }
            }
        }
        return hits.take(MAX_HITS)
            .mapNotNull { hit -> widen(rpc, hit) }
            .distinctBy { it.start }
            .sortedByDescending { MemoryScan.similarity(MemoryScan.clean(it.text), line) }
    }

    /** Hands [visit] every readable stretch of the game's memory, each with [overlap] bytes of the one before. */
    private fun scan(rpc: AzaharRpc, overlap: Int, visit: (ByteArray, Long) -> Unit) {
        for (region in MemoryScan.REGIONS) {
            var address = region.first
            var carry = ByteArray(0)
            while (address < region.last) {
                val chunk = rpc.read(address, AzaharRpc.MAX_READ)
                if (chunk == null || chunk.all { it == 0.toByte() }) {
                    carry = ByteArray(0)
                } else {
                    visit(carry + chunk, address - carry.size)
                    carry = if (overlap > 0) chunk.copyOfRange(chunk.size - overlap, chunk.size) else ByteArray(0)
                }
                address += AzaharRpc.MAX_READ
            }
        }
    }

    /** The whole string around [hit]. */
    private fun widen(rpc: AzaharRpc, hit: TextHit): FoundText? {
        val from = hit.address - CONTEXT
        val bytes = rpc.read(from, CONTEXT * 2) ?: return null
        val start = MemoryScan.stringStart(bytes, CONTEXT, hit.charset)
        val text = PspHooks.decode(bytes.copyOfRange(start, bytes.size), hit.charset)
        return FoundText(hit, from + start, text)
    }

    /** For the debug receiver: what a search for [line] finds, without locking on. */
    suspend fun searchNow(line: String): List<FoundText> = withContext(Dispatchers.IO) {
        AzaharRpc().use { rpc -> if (selectGame(rpc)) search(rpc, line) else emptyList() }
    }

    private data class Lock(val found: FoundText, val pointers: List<Long>)

    /** Azahar's settings: a folder it was pointed at under shared storage. */
    private fun configFile(): File? {
        val storage = Environment.getExternalStorageDirectory()
        return CONFIG_PATHS.map { File(storage, it) }.firstOrNull { it.isFile }
    }

    private companion object {
        const val TAG = "AzaharText"
        const val DEBUGGING = "Debugging"
        const val RPC_KEY = "enable_rpc_server"
        const val CONTEXT = 0x400
        const val MAX_HITS = 64
        const val MAX_POINTERS = 16
        const val LINE_BYTES = 512
        const val POLL_MS = 250L
        const val RETRY_MS = 10_000L
        const val THREE_DS = "3ds"
        val CONFIG_PATHS = listOf("Azahar/config/config.ini", "azahar/config/config.ini")
        val ENABLED = booleanPreferencesKey("enabled")
        val SAVED_RPC = stringPreferencesKey("saved_rpc")
    }
}
