package app.genkan.core

import app.genkan.memory.FoundText
import app.genkan.memory.MemoryText
import app.genkan.memory.MemoryTextStatus
import app.genkan.memory.MemorySource
import app.genkan.memory.RamSnapshots
import app.genkan.memory.TextSamples
import app.genkan.yomikata.YomikataCatalog
import android.util.Log
import app.genkan.ocr.OcrReader
import app.genkan.reader.ReaderSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text from the memory of the game in the built-in player, found and followed as for Azahar
 * ([MemoryText]). The player's memory is Genkan's own to read, so this needs no setting.
 */
@Singleton
class BuiltInText @Inject constructor(
    private val session: ReaderSession,
    ocr: OcrReader,
    private val player: BuiltInPlayer,
    private val samples: TextSamples,
    private val snapshots: RamSnapshots,
    private val catalog: YomikataCatalog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val text = MemoryText(session, ocr, TAG, ::missing)
    private var started = false
    /** The release id read from the running game's RAM, by game key. */
    private val ids = HashMap<String, String?>()

    val status: StateFlow<MemoryTextStatus> = text.status

    /** Lines handed in as if OCR had read them (debug builds). */
    internal val offered: MutableSharedFlow<String> get() = text.offered

    fun start() {
        if (started) return
        started = true
        scope.launch {
            player.game.collectLatest { game ->
                if (game != null) text.follow(player::memory) else text.off()
            }
        }
    }

    /**
     * A line OCR read that isn't in memory as standard text, saved for Yomikata when samples are
     * on: for a game whose catalog entry gives a standard encoding, by its shape (OCR may have
     * misread it); for one with its own table, compressed or unknown text, or no entry, as a
     * snapshot of the whole RAM, for the inbox's side to search.
     */
    private suspend fun missing(memory: MemorySource, line: String) {
        if (!samples.enabled()) return
        val game = session.game.value
        val id = ids.getOrPut(game.key) { RamSnapshots.mainRamOf(memory, game.platform)?.let { RamSnapshots.gameId(game.platform, it) } }
        val encoding = id?.let { runCatching { catalog.entry(it) }.getOrNull()?.encoding }
        if (encoding in STANDARD) {
            samples.sample(memory, game, line)
        } else if (id != null) {
            val core = player.game.value?.core?.displayName ?: "built-in player"
            snapshots.take(memory, game, id, line, "Genkan built-in player ($core)")
        } else {
            Log.i(TAG, "No release id in ${game.title}'s RAM: no snapshot")
        }
    }

    /** For the debug receiver: what a search for [line] finds, without locking on. */
    suspend fun searchNow(line: String): List<FoundText> = withContext(Dispatchers.IO) {
        player.memory()?.use { text.search(it, line) }.orEmpty()
    }

    private companion object {
        const val TAG = "BuiltInText"
        val STANDARD = setOf("shift_jis", "utf16le", "utf8")
    }
}
