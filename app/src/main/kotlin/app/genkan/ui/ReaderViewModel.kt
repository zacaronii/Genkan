package app.genkan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import app.genkan.anki.AnkiMiner
import app.genkan.anki.MineSource
import app.genkan.anki.MinedState
import app.genkan.audio.GameAudioRecorder
import app.genkan.capture.CaptureService
import app.genkan.capture.ScreenCapture
import app.genkan.stats.ReadingStats
import app.genkan.azahar.AzaharText
import app.genkan.core.GameOverlay
import app.genkan.core.BuiltInText
import app.genkan.ppsspp.HookFinder
import app.genkan.ppsspp.PpssppText
import app.genkan.dictionary.Dictionaries
import app.genkan.ocr.OcrReader
import app.genkan.ocr.OcrRegion
import app.genkan.dictionary.LookupResult
import app.genkan.reader.ReaderLineEntity
import app.genkan.reader.ReaderSession
import app.genkan.reader.ThreadSample
import app.genkan.dictionary.ScannedWord
import app.genkan.words.LookupCounts
import app.genkan.words.PopupPrefsSource
import app.genkan.words.WordMarkState
import app.genkan.words.WordMarks
import app.genkan.words.WordStatus
import app.genkan.words.WordStatuses
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A word in a backlog line to underline: where it is, and how Anki has it. */
data class LineMark(val start: Int, val end: Int, val state: WordMarkState)

/** A word looked up in the backlog: where it starts, and what the dictionaries found there. */
/** The lookup's line id for answer [index] of a choice on screen: below every backlog id and the overlay's. */
fun answerLine(index: Int): Long = -100L - index

data class Lookup(val lineId: Long, val start: Int, val result: LookupResult?, val lineText: String = "") {
    /** The characters the longest match covers, for the highlight. */
    val end: Int get() = start + (result?.matchedLength ?: 0)
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val session: ReaderSession,
    val dictionaries: Dictionaries,
    val ocr: OcrReader,
    val capture: ScreenCapture,
    private val miner: AnkiMiner,
    val audio: GameAudioRecorder,
    val stats: ReadingStats,
    val ppsspp: PpssppText,
    val hookFinder: HookFinder,
    val azahar: AzaharText,
    val builtIn: BuiltInText,
    private val words: WordStatuses,
    private val counts: LookupCounts,
    private val wordMarks: WordMarks,
    private val popupPrefs: PopupPrefsSource,
    val overlay: GameOverlay,
) : ViewModel(), PopupActions {

    /** Each backlog line's words, as scanned for its text (scanning is the slow part, done once). */
    private val scanned = HashMap<Long, Pair<String, List<ScannedWord>>>()

    private val _lineMarks = MutableStateFlow<Map<Long, List<LineMark>>>(emptyMap())
    /** The underlines for each backlog line, by line id: words not in Anki, or being learned. */
    val lineMarks: StateFlow<Map<Long, List<LineMark>>> = _lineMarks.asStateFlow()

    /** The entries the popup shows now (a link can replace the tapped word's). */
    private var shownResult: LookupResult? = null

    init {
        // A card queued offline reaching Anki later turns its badge to "In Anki".
        viewModelScope.launch { miner.deliveries.collect { refreshStates() } }
        viewModelScope.launch { words.marks.collect { refreshMarks() } }
        viewModelScope.launch { overlay.taps.collect { lookUpOnScreen(it.text, it.offset) } }
        viewModelScope.launch {
            combine(session.lines, wordMarks.revision, wordMarks.enabled) { lines, _, on -> lines to on }.collectLatest { (lines, on) ->
                if (!on) {
                    _lineMarks.value = emptyMap()
                    return@collectLatest
                }
                // Marks restyle without scanning again; new lines are scanned newest first.
                publishMarks(lines)
                var fresh = 0
                for (line in lines.asReversed()) {
                    if (scanned[line.id]?.first == line.text) continue
                    scanned[line.id] = line.text to dictionaries.scan(line.text)
                    if (++fresh % PUBLISH_EVERY == 0) publishMarks(lines)
                }
                scanned.keys.retainAll(lines.map { it.id }.toSet())
                publishMarks(lines)
            }
        }
    }

    private val _editingArea = MutableStateFlow<Bitmap?>(null)
    /** The top-screen snapshot the reading area is being drawn on, while the editor is open. */
    val editingArea: StateFlow<Bitmap?> = _editingArea.asStateFlow()

    /**
     * Switches OCR on or off. On needs screen capture: when it isn't running, [askConsent]
     * shows Android's prompt, and reading starts once capture does.
     */
    fun toggleOcr(askConsent: () -> Unit) {
        if (ocr.enabled.value) {
            // Capture only ever starts for OCR, so it stops with it: no cast icon left behind.
            ocr.setEnabled(false)
            if (capture.running.value) CaptureService.stop(context)
            return
        }
        ocr.setEnabled(true)
        if (!capture.running.value) askConsent()
    }

    fun editArea() {
        closeLookup()
        _editingArea.value = capture.frame()
    }

    fun saveArea(region: OcrRegion) {
        viewModelScope.launch { ocr.saveRegion(region) }
        _editingArea.value = null
    }

    fun resetArea() {
        viewModelScope.launch { ocr.resetRegion() }
        _editingArea.value = null
    }

    fun closeArea() {
        _editingArea.value = null
    }

    private val _lookup = MutableStateFlow<Lookup?>(null)
    val lookup: StateFlow<Lookup?> = _lookup.asStateFlow()

    private val _popup = MutableStateFlow<PopupContent?>(null)
    /** What the popup shows; a kanji or a dictionary link can replace the tapped word's entries. */
    val popup: StateFlow<PopupContent?> = _popup.asStateFlow()

    private val _picking = MutableStateFlow(false)
    /** The thread picker is open by choice (it also shows while no thread is chosen). */
    val picking: StateFlow<Boolean> = _picking.asStateFlow()

    private var lookupJob: Job? = null

    /** A tap at [offset] in [line]: look up the text from there to the end of the line. */
    fun lookUp(line: ReaderLineEntity, offset: Int) = lookUp(line.id, line.text, offset)

    /**
     * A tap on the overlay over the game, at [offset] in the [text] OCR read there (one row of
     * the box): the same lookup, on the newest backlog line holding that row when there is one
     * (it's highlighted there, and the whole line and its voice go on a card), else on the row.
     */
    fun lookUpOnScreen(text: String, offset: Int) {
        val line = session.lines.value.lastOrNull { text in it.text }
        if (line != null) lookUp(line.id, line.text, line.text.indexOf(text) + offset) else lookUp(SCREEN_LINE, text, offset)
    }

    /** A tap at [offset] in answer [index] of the choice on screen: the answer is looked up as a line of its own. */
    fun lookUpAnswer(index: Int, text: String, offset: Int) = lookUp(answerLine(index), text, offset)

    private fun lookUp(lineId: Long, text: String, offset: Int) {
        if (offset !in text.indices) return
        // Start on a whole character: never between the halves of a surrogate pair (𠮟).
        val start = if (offset > 0 && text[offset].isLowSurrogate()) offset - 1 else offset
        lookupJob?.cancel()
        _lookup.value = Lookup(lineId, start, null, text)
        _popup.value = null
        stats.onInteraction()
        lookupJob = viewModelScope.launch {
            val result = dictionaries.lookup(text.substring(start), popupPrefs.prefs.value.maxResults)
            _lookup.value = Lookup(lineId, start, result, text)
            show(result)
        }
    }

    /** Shows [result] with each entry's Anki badge, then checks Anki itself for them. */
    private suspend fun show(result: LookupResult) {
        shownResult = result
        val styles = dictionaries.stylesJson()
        val expressions = result.hits.map { it.expression }
        // A popup visit counts once, for the first result's headword, as Hachidori counts it.
        val first = result.hits.firstOrNull()
        val prefs = popupPrefs.prefs.value
        val count = if (first != null && prefs.counts) counts.record(first.expression, first.reading) else 0
        val summary = if (prefs.summary) {
            org.json.JSONObject().put("count", prefs.summaryCount).put("dictionary", prefs.summaryDictionary ?: org.json.JSONObject.NULL).toString()
        } else {
            "null"
        }
        _popup.value = PopupContent.Terms(result.json, styles, statesJson(expressions), marksJson(result), count, summary)
        viewModelScope.launch {
            miner.refreshFromAnki(expressions)
            refreshStates()
        }
    }

    /** Each hit's hand mark, as the page wants it. */
    private fun marksJson(result: LookupResult): String =
        result.hits.joinToString(",", "[", "]") { hit -> words.status(hit.expression)?.let { "\"$it\"" } ?: "null" }

    override fun setMark(index: Int, status: String) {
        val result = shownResult ?: return
        val hit = result.hits.getOrNull(index) ?: return
        val mark = runCatching { WordStatus.valueOf(status) }.getOrNull() ?: return
        viewModelScope.launch { words.toggle(hit.expression, mark) }
    }

    private fun publishMarks(lines: List<ReaderLineEntity>) {
        _lineMarks.value = lines.associate { line ->
            val words = scanned[line.id]?.takeIf { it.first == line.text }?.second.orEmpty()
            line.id to words.mapNotNull { w ->
                wordMarks.state(w.expression, w.reading)?.takeIf { it != WordMarkState.KNOWN }?.let { LineMark(w.start, w.end, it) }
            }
        }
    }

    private val _notice = MutableStateFlow<String?>(null)
    /** A passing message for the bar (what a snapshot did). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** A snapshot of the built-in player's game now, for Yomikata. */
    fun snapshotNow() {
        viewModelScope.launch {
            _notice.value = builtIn.snapshotNow()
            kotlinx.coroutines.delay(NOTICE_MS)
            _notice.value = null
        }
    }

    /** The shown entries' marks, again: a mark changed (here, or in settings). */
    private fun refreshMarks() {
        val result = shownResult ?: return
        val current = _popup.value as? PopupContent.Terms ?: return
        if (current.hitsJson == result.json) _popup.value = current.copy(marksJson = marksJson(result))
    }

    private suspend fun statesJson(expressions: List<String>): String {
        val states = miner.states(expressions)
        return expressions.joinToString(",", "[", "]") { "\"${states[it] ?: MinedState.NEW}\"" }
    }

    private suspend fun refreshStates() {
        val current = _popup.value as? PopupContent.Terms ?: return
        val result = shownResult ?: return
        if (current.hitsJson != result.json) return
        _popup.value = current.copy(statesJson = statesJson(result.hits.map { it.expression }))
    }

    override fun mine(index: Int, glossaryHtml: String, firstGlossaryHtml: String, furiganaPlain: String) {
        val lookup = _lookup.value ?: return
        val hit = shownResult?.hits?.getOrNull(index) ?: return
        // The top screen as it is now: the game's scene for the card.
        val picture = if (capture.running.value) capture.frame() else null
        val voice = sentenceAudio(lookup.lineId)
        viewModelScope.launch {
            miner.mine(
                MineSource(
                    hit = hit,
                    sentence = lookup.lineText,
                    matchStart = lookup.start,
                    matchLength = if (shownResult == lookup.result) hit.matched.length else 0,
                    glossaryHtml = glossaryHtml,
                    firstGlossaryHtml = firstGlossaryHtml,
                    furiganaPlain = furiganaPlain,
                    gameTitle = session.game.value.title,
                ),
                picture,
                voice,
            )
            refreshStates()
        }
    }

    override fun lookUpKanji(character: String) {
        if (_lookup.value == null) return
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            _popup.value = PopupContent.Kanji(character, dictionaries.kanjiJson(character))
        }
    }

    /** A dictionary's link to another word: its entries replace the popup's, the highlight stays. */
    override fun lookUp(text: String) {
        if (_lookup.value == null) return
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch { show(dictionaries.lookup(text, popupPrefs.prefs.value.maxResults)) }
    }

    /**
     * The line's voice: from a moment before it arrived (OCR reads a line once it stops
     * changing, after the voice began) until the next line did, or now, at most
     * [MAX_CLIP_MS]; silence trimmed off both ends.
     */
    private fun sentenceAudio(lineId: Long): ShortArray? {
        if (!audio.running.value) return null
        val lines = session.lines.value
        val i = lines.indexOfFirst { it.id == lineId }
        if (i < 0) return null
        val from = lines[i].at - LEAD_MS
        val to = minOf(lines.getOrNull(i + 1)?.at ?: System.currentTimeMillis(), from + MAX_CLIP_MS)
        return audio.clip(from, to)
    }

    /** Starts recording game audio once the permission is granted (capture must be on). */
    fun onAudioPermission(granted: Boolean) {
        if (granted) capture.startAudio()
    }

    fun closeLookup() {
        lookupJob?.cancel()
        _lookup.value = null
        _popup.value = null
    }

    fun openPicker() {
        closeLookup()
        _picking.value = true
    }

    fun closePicker() {
        _picking.value = false
    }

    fun choose(sample: ThreadSample) {
        viewModelScope.launch {
            session.choose(sample)
            _picking.value = false
        }
    }

    private companion object {
        /** The line id of a lookup from the overlay whose text isn't in the backlog. */
        const val SCREEN_LINE = -1L
        /** Lines scanned between showing their marks, on a full backlog. */
        const val PUBLISH_EVERY = 10
        const val NOTICE_MS = 5_000L
        const val LEAD_MS = 2_500L
        const val MAX_CLIP_MS = 20_000L
    }
}
