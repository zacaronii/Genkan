package app.genkan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.Bitmap
import app.genkan.anki.AnkiMiner
import app.genkan.anki.MineSource
import app.genkan.anki.MinedState
import app.genkan.audio.GameAudioRecorder
import app.genkan.capture.ScreenCapture
import app.genkan.stats.ReadingStats
import app.genkan.dictionary.Dictionaries
import app.genkan.ocr.OcrReader
import app.genkan.ocr.OcrRegion
import app.genkan.dictionary.LookupResult
import app.genkan.reader.ReaderLineEntity
import app.genkan.reader.ReaderSession
import app.genkan.reader.ThreadSample
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A word looked up in the backlog: where it starts, and what the dictionaries found there. */
data class Lookup(val lineId: Long, val start: Int, val result: LookupResult?, val lineText: String = "") {
    /** The characters the longest match covers, for the highlight. */
    val end: Int get() = start + (result?.matchedLength ?: 0)
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    val session: ReaderSession,
    val dictionaries: Dictionaries,
    val ocr: OcrReader,
    val capture: ScreenCapture,
    private val miner: AnkiMiner,
    val audio: GameAudioRecorder,
    val stats: ReadingStats,
) : ViewModel(), PopupActions {

    /** The entries the popup shows now (a link can replace the tapped word's). */
    private var shownResult: LookupResult? = null

    init {
        // A card queued offline reaching Anki later turns its badge to "In Anki".
        viewModelScope.launch { miner.deliveries.collect { refreshStates() } }
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
            ocr.setEnabled(false)
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
    fun lookUp(line: ReaderLineEntity, offset: Int) {
        if (offset !in line.text.indices) return
        // Start on a whole character: never between the halves of a surrogate pair (𠮟).
        val start = if (offset > 0 && line.text[offset].isLowSurrogate()) offset - 1 else offset
        lookupJob?.cancel()
        _lookup.value = Lookup(line.id, start, null, line.text)
        _popup.value = null
        stats.onInteraction()
        lookupJob = viewModelScope.launch {
            val result = dictionaries.lookup(line.text.substring(start))
            _lookup.value = Lookup(line.id, start, result, line.text)
            show(result)
        }
    }

    /** Shows [result] with each entry's Anki badge, then checks Anki itself for them. */
    private suspend fun show(result: LookupResult) {
        shownResult = result
        val styles = dictionaries.stylesJson()
        val expressions = result.hits.map { it.expression }
        _popup.value = PopupContent.Terms(result.json, styles, statesJson(expressions))
        viewModelScope.launch {
            miner.refreshFromAnki(expressions)
            refreshStates()
        }
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
        lookupJob = viewModelScope.launch { show(dictionaries.lookup(text)) }
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
        const val LEAD_MS = 2_500L
        const val MAX_CLIP_MS = 20_000L
    }
}
