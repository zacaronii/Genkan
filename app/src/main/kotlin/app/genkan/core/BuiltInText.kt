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
import app.genkan.yomikata.CatalogEntry
import app.genkan.yomikata.EntryLookup
import app.genkan.yomikata.YomikataCatalog
import app.genkan.yomikata.YomikataIds
import android.util.Log
import app.genkan.ocr.OcrReader
import app.genkan.reader.ReaderSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
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
    private val yomikataIds: YomikataIds,
    private val tuning: GameTuning,
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
                    text.rules = MemoryScan.NO_RULES
                    resetGame()
                    gameStartedMs = System.currentTimeMillis()
                    launch { findReader() }
                    text.follow(player::memory)
                } else {
                    resetGame()
                    text.off()
                }
            }
        }
        scope.launch { player.scriptEvents.collect(::onScriptEvents) }
        scope.launch {
            player.scriptStateLoads.collect {
                stateLoadedMs = System.currentTimeMillis()
                scriptReader?.stateLoaded()
                if (scriptReader != null) {
                    _choice.value = null
                    _scriptScreen.value = app.genkan.ocr.ScreenText.EMPTY
                }
                // A script that refused the game as it started may take it now (a PS1 past its BIOS).
                refused?.let { (game, id) -> if (scriptReader == null) scope.launch { startScript(game, id) } }
            }
        }
    }

    /** A game is in the built-in player. */
    val playing: StateFlow<Boolean> = player.game.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Yomikata samples and snapshots are on. */
    val sampling: kotlinx.coroutines.flow.Flow<Boolean> get() = samples.enabledFlow

    /** A choice the script reads on screen now: its answers, and the row the cursor is on. */
    data class Choice(val answers: List<String>, val cursor: Int?)

    private val _choice = MutableStateFlow<Choice?>(null)
    /** The choice up now (null: none), while a script gives the game's text. */
    val choice: StateFlow<Choice?> = _choice.asStateFlow()

    private val _notListed = MutableStateFlow(false)
    /** The playing game's id isn't in the Yomikata catalog: no text reader for it yet (OCR still works). */
    val notListed: StateFlow<Boolean> = _notListed.asStateFlow()

    /**
     * Once the game's id is known (read from its RAM, or [GameId.choose]'s stored one), the
     * catalog's script for it runs in the player, when it has one Genkan can run; otherwise the
     * game's text is followed in memory, with OCR. Tries for a while: a game sets its memory up as
     * it boots.
     */
    private suspend fun findReader() {
        val game = session.game.value
        _notListed.value = false
        val stored = game.rommRomId?.let { yomikataIds.idFor(it).first() }
        // A cartridge's id is in its header, read once; a disc's is looked for in RAM as it boots.
        val cartridge = withContext(Dispatchers.IO) { player.cartridgeId() }
        repeat(ID_TRIES) { attempt ->
            val id = withContext(Dispatchers.IO) {
                // RAM first here too: the id found earlier in this run (another disc's, maybe)
                // only stands in, as the stored one does.
                GameId.choose(
                    ram = cartridge?.id ?: player.memory()?.use { m -> RamSnapshots.mainRamOf(m, game.platform)?.let { RamSnapshots.gameId(game.platform, it) } },
                    stored = ids[game.key] ?: stored,
                    attempt = attempt,
                )
            }
            if (id != null) {
                ids[game.key] = id
                yomikataIds.learned(game.rommRomId, id)
                gameCartridge = cartridge
                startScript(game, id)
                // The entry's cleanup, for the game's own codes in the lines memory gives.
                text.rules = MemoryText.rulesOf(entryOf(game.title, id))
                tuneFor(game, id)
                return
            }
            delay(ID_RETRY_MS)
        }
    }

    /**
     * The catalog's step for the release now known as [id] (on a first play the launch didn't know
     * it), applied as the game runs when the core can; else from its next start.
     */
    private suspend fun tuneFor(game: app.genkan.reader.NowPlaying.Game, id: String) {
        val romId = game.rommRomId ?: return
        val core = player.game.value?.core ?: return
        val resolved = tuning.resolve(romId, core.tuning, id) ?: return
        val live = withContext(Dispatchers.IO) { player.setTuningStep(resolved.step.id) }
        Log.i(TAG, "Tuning step ${resolved.step.id} (${resolved.source.name.lowercase()})" + if (live) "" else ", from the next start")
    }

    private val _scriptScreen = MutableStateFlow(app.genkan.ocr.ScreenText.EMPTY)
    /**
     * Where the script says its texts are drawn, as the overlay's boxes (empty: none up): dialogue,
     * answers and interface text alike, each a tap away from a lookup. Interface text is offered
     * only here, never in the backlog.
     */
    val scriptScreen: StateFlow<app.genkan.ocr.ScreenText> = _scriptScreen.asStateFlow()

    private val _scriptActive = MutableStateFlow(false)
    /** The game's lines come from its Yomikata script. */
    val scriptActive: StateFlow<Boolean> = _scriptActive.asStateFlow()

    /** The running script's events, read into lines and the choice; null: no script runs. */
    @Volatile private var scriptReader: ScriptReader? = null
    /** The script running, for a snapshot's meta. */
    @Volatile private var activeScript: app.genkan.yomikata.CatalogScript? = null
    /** A game and id whose script refused it as another build: tried again after a savestate load. */
    @Volatile private var refused: Pair<app.genkan.reader.NowPlaying.Game, String>? = null
    @Volatile private var gameStartedMs = 0L
    @Volatile private var stateLoadedMs: Long? = null
    private var quietJob: kotlinx.coroutines.Job? = null
    /** The cartridge read as the game started, for its script's build id. */
    @Volatile private var gameCartridge: SnesCartridge.Id? = null

    /**
     * Runs the catalog's script for [id] in the player, when it has one Genkan can run; whether it
     * runs. A script that refuses the game as another build is tried again (see [ScriptRetry]).
     */
    private suspend fun startScript(game: app.genkan.reader.NowPlaying.Game, id: String): Boolean {
        val files = when (val found = withContext(Dispatchers.IO) { runCatching { catalog.script(id) }.getOrElse { app.genkan.yomikata.ScriptLookup.Failed(it.message ?: "") } }) {
            is app.genkan.yomikata.ScriptLookup.Found -> found.files
            app.genkan.yomikata.ScriptLookup.NotListed -> return false
            is app.genkan.yomikata.ScriptLookup.Failed -> return false.also { Log.w(TAG, "${game.title} ($id): no script, ${found.reason}") }
        }
        val script = files.script
        val ram = withContext(Dispatchers.IO) { player.memory()?.use { RamSnapshots.mainRamOf(it, game.platform) } }
        val cartridge = gameCartridge?.takeIf { it.id == id }
        val buildId = ScriptEvents.buildId(game.platform, ram, cartridge?.headerCrc32)
        val regions = if (withContext(Dispatchers.IO) { player.hasVideoRam() }) setOf("vram") else emptySet()
        script.unusable(withContext(Dispatchers.IO) { player.scriptLevel() }, buildId, regions)?.let { why ->
            Log.i(TAG, "${game.title} ($id): its script can't run here: $why")
            return false
        }
        while (true) {
            val error = withContext(Dispatchers.IO) { player.loadScript(script.path.substringAfterLast('/'), files.source, files.data, id, buildId) }
            if (error == null) {
                refused = null
                beginScript(script)
                Log.i(TAG, "${game.title} ($id): reading its Yomikata script")
                return true
            }
            if (!ScriptRetry.again(error, System.currentTimeMillis(), gameStartedMs, stateLoadedMs)) {
                if (ScriptRetry.anotherBuild(error)) refused = game to id
                Log.i(TAG, "${game.title} ($id): its script didn't load: $error")
                return false
            }
            delay(ScriptRetry.EVERY_MS)
        }
    }

    private fun beginScript(script: app.genkan.yomikata.CatalogScript) {
        val reader = ScriptReader()
        scriptReader = reader
        activeScript = script
        _scriptActive.value = true
        // OCR stays on for the screens the script doesn't read, out of the backlog while its windows are up.
        quietJob?.cancel()
        quietJob = scope.launch {
            while (true) {
                ocr.quiet = reader.windowOpen(System.currentTimeMillis())
                delay(QUIET_CHECK_MS)
            }
        }
    }

    /** The script stopped (dead, or a new game): nothing of it carries over. */
    private fun endScript() {
        quietJob?.cancel()
        quietJob = null
        scriptReader = null
        activeScript = null
        _scriptActive.value = false
        _scriptScreen.value = app.genkan.ocr.ScreenText.EMPTY
        ocr.quiet = false
    }

    /** One frame's events from the script: its lines to the backlog, its choice on screen. */
    private fun onScriptEvents(events: List<Pair<Int, String>>) {
        val reader = scriptReader ?: return
        val step = reader.read(events, System.currentTimeMillis())
        step.lines.forEach(session::submit)
        _choice.value = step.choice
        if (step.layoutsChanged) {
            val layouts = reader.layouts
            scope.launch(Dispatchers.IO) {
                val bounds = if (layouts.isEmpty()) null else player.pictureBounds()
                _scriptScreen.value = if (bounds == null || bounds.size < 4) app.genkan.ocr.ScreenText.EMPTY else ScriptScreen.of(layouts, bounds)
            }
        }
        if (step.dead) {
            Log.w(TAG, "The script was switched off: memory text and OCR from here")
            player.unloadScript()
            endScript()
            _choice.value = null
        }
    }

    /** The running script for a snapshot's meta: its id, hash, and the id of the last text it gave. */
    private fun scriptMeta(): org.json.JSONObject? {
        val script = activeScript ?: return null
        return org.json.JSONObject().put("id", script.id).put("sha256", script.sha256)
            .put("last_text_id", scriptReader?.lastTextId ?: org.json.JSONObject.NULL)
    }

    /** Debug builds: runs a script from a file as if the catalog had it for [gameId]; null when it runs, else why not. */
    suspend fun debugScript(name: String, source: ByteArray, data: Map<String, ByteArray>, gameId: String, buildId: String?): String? {
        val error = withContext(Dispatchers.IO) { player.loadScript(name, source, data, gameId, buildId) }
        if (error == null) {
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
            beginScript(app.genkan.yomikata.CatalogScript(gameId, name, sha, "", listOfNotNull(buildId), emptyList(), 1, emptyMap()))
        }
        return error
    }

    /**
     * The catalog's entry for [id]. A fetch that failed is asked again after each of
     * [EntryRetry.waits] (offline for a moment, GitHub slow, where the next try works); an id the
     * catalog doesn't list, or an entry that doesn't read, is not.
     */
    private suspend fun entryOf(title: String, id: String): CatalogEntry? {
        val result = EntryRetry.retrying(
            ask = { runCatching { catalog.lookup(id) }.getOrElse { EntryLookup.Failed(it.message ?: it.javaClass.simpleName, retry = true) } },
            again = { it is EntryLookup.Failed && it.retry },
        )
        return when (result) {
            is EntryLookup.Found -> result.entry
            EntryLookup.NotListed -> null.also {
                Log.i(TAG, "$title ($id): not in the Yomikata catalog")
                _notListed.value = true
            }
            is EntryLookup.Failed -> null.also { Log.w(TAG, "$title ($id): no catalog entry, ${result.reason}; no cleanup this play") }
        }
    }

    /** A new game, or none: nothing of the last one's script carries over. */
    private fun resetGame() {
        endScript()
        refused = null
        gameCartridge = null
        stateLoadedMs = null
        _choice.value = null
    }

    /**
     * A snapshot now, at whatever moment the game is in (a line typing out, waiting for a
     * press), for Yomikata to read the game's memory against; what happened, for the bar.
     */
    suspend fun snapshotNow(): String = withContext(Dispatchers.IO) {
        val game = session.game.value
        val memory = player.memory() ?: return@withContext "No game in the built-in player"
        memory.use { m ->
            // A cartridge's id is in its header; a disc's in its RAM.
            val id = ids[game.key] ?: player.cartridgeId()?.id ?: RamSnapshots.mainRamOf(m, game.platform)?.let { RamSnapshots.gameId(game.platform, it) }
                ?: return@withContext "This game's id isn't known yet"
            ids[game.key] = id
            yomikataIds.learned(game.rommRomId, id)
            // OCR reads the screen once the moment is taken (its last line may be an earlier screen's).
            // Reading nothing means nothing was read, or another part of the screen, not that it was blank.
            val moment = "the screen read by OCR"
            val core = player.game.value?.core?.displayName ?: "built-in player"
            val taken = snapshots.takeNow(
                m, game, id, "", "Genkan built-in player ($core)", extras, "taken on demand: $moment",
                ocrUnavailable = true, screenLine = { kotlinx.coroutines.runBlocking { ocr.readNow() } }, script = scriptMeta(),
            )
            if (taken) {
                "Snapshot taken ($moment); it goes to Yomikata with the next sync"
            } else {
                "Couldn't take a snapshot of this system's memory"
            }
        }
    }

    /** Everything the built-in player can add to a snapshot. */
    private val extras = object : RamSnapshots.Extras {
        override fun vram() = player.videoRam()
        override fun state(file: java.io.File) = player.saveState(file)
        override fun screen() = player.frame(native = true) ?: player.frame()
        override fun moment(ram: app.genkan.memory.MainRam, scratch: java.io.File) = player.moment(ram.range, scratch)
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
        const val ID_TRIES = 20
        const val ID_RETRY_MS = 3_000L
        /** How often OCR's quiet is set from the script's windows. */
        const val QUIET_CHECK_MS = 250L
        /** Codes either side of a found run that can still be its line. */
        const val MARGIN = 8
        /** A run found in more places than this says nothing about where the line is. */
        const val MAX_PLACES = 3
    }
}

/** Which id a game is read as, while the player looks for it. */
internal object GameId {
    /**
     * The id in the game's RAM wins: a multi-disc game is one ROM in RomM and each disc names
     * itself, and a stored id may be stale. The one stored from an earlier play ([stored]) stands
     * in only when RAM shows none, and only from the second try ([attempt] 1), so a disc's own id
     * has a first chance to appear.
     */
    fun choose(ram: String?, stored: String?, attempt: Int): String? = ram ?: stored?.takeIf { attempt >= 1 }
}

/** When the player asks the catalog for a game's entry again after a failed fetch: three more times, further apart. */
internal object EntryRetry {
    val waits: List<Long> = listOf(5_000L, 15_000L, 40_000L)

    /** [ask]'s answer, asking at once and again after each of [waits] while [again] says so; the last answer. */
    suspend fun <T> retrying(ask: suspend () -> T, again: (T) -> Boolean): T {
        var answer = ask()
        for (wait in waits) {
            if (!again(answer)) return answer
            delay(wait)
            answer = ask()
        }
        return answer
    }
}
