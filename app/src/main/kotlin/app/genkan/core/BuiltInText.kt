package app.genkan.core

import app.genkan.memory.FoundText
import app.genkan.memory.MemoryText
import app.genkan.memory.MemoryTextStatus
import app.genkan.memory.MemorySource
import app.genkan.memory.MainRam
import app.genkan.memory.MemoryScan
import app.genkan.memory.RamSnapshots
import app.genkan.memory.TableText
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
    private val ocr: OcrReader,
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
                if (game != null) {
                    // A game with its own codes: OCR's lines are put right from memory with its table.
                    ocr.corrector = ::tableLine
                    text.follow(player::memory)
                } else {
                    text.off()
                }
            }
        }
    }

    /** Everything the built-in player can add to a snapshot. */
    private val extras = object : RamSnapshots.Extras {
        override fun vram() = player.videoRam()
        override fun state(file: java.io.File) = player.saveState(file)
        override fun screen() = player.frame(native = true) ?: player.frame()
    }

    /** The table reader for each game key that has one. */
    private val tables = HashMap<String, TableText>()

    /**
     * [line] as a game with a Yomikata table wrote it (see [TableText]), or null to keep OCR's:
     * its kana runs are looked for in main RAM as the table's codes, and the codes around the
     * first place found lined up with OCR's line.
     */
    private suspend fun tableLine(line: String): String? = withContext(Dispatchers.IO) {
        val game = session.game.value
        val ramAt = MainRam.of(game.platform) ?: return@withContext null
        player.memory()?.use { memory ->
            val ram = RamSnapshots.mainRamOf(memory, game.platform) ?: return@use null
            // Only a table found is kept: the catalog may gain one while the game runs.
            val reader = tables[game.key] ?: run {
                val id = ids.getOrPut(game.key) { RamSnapshots.gameId(game.platform, ram) } ?: return@use null
                val entry = runCatching { catalog.entry(id) }.getOrNull() ?: return@use null
                val table = catalog.table(entry) ?: return@use null
                Log.i(TAG, "${game.title} ($id): reading with its Yomikata table")
                TableText(table, entry.unit).also { tables[game.key] = it }
            }
            val reach = (line.length + MARGIN) * 2
            for (probe in reader.probes(line)) {
                val hits = MemoryScan.find(ram, probe)
                if (hits.isEmpty() || hits.size > MAX_PLACES) continue
                val hit = hits.first()
                // As far back as the line could start, keeping the codes' alignment.
                val from = hit - minOf(hit / 2, reach) * 2
                val tokens = reader.tokens(ram, maxOf(0, from), minOf(ram.size, hit + reach * 2))
                reader.correct(tokens, line)?.let { exact ->
                    Log.i(TAG, "Table line at 0x%08x: %s".format(ramAt.baseAddress + hit, exact))
                    return@use exact
                }
            }
            null
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
            snapshots.take(memory, game, id, line, "Genkan built-in player ($core)", extras)
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
        /** Codes either side of a found run that can still be its line. */
        const val MARGIN = 8
        /** A run found in more places than this says nothing about where the line is. */
        const val MAX_PLACES = 3
    }
}
