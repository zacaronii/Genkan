package app.genkan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.dictionary.Dictionaries
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
data class Lookup(val lineId: Long, val start: Int, val result: LookupResult?) {
    /** The characters the longest match covers, for the highlight. */
    val end: Int get() = start + (result?.matchedLength ?: 0)
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    val session: ReaderSession,
    val dictionaries: Dictionaries,
) : ViewModel(), PopupActions {

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
        _lookup.value = Lookup(line.id, start, null)
        _popup.value = null
        lookupJob = viewModelScope.launch {
            val result = dictionaries.lookup(line.text.substring(start))
            _lookup.value = Lookup(line.id, start, result)
            _popup.value = PopupContent.Terms(result.json, dictionaries.stylesJson())
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
        lookupJob = viewModelScope.launch {
            _popup.value = PopupContent.Terms(dictionaries.lookup(text).json, dictionaries.stylesJson())
        }
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
}
