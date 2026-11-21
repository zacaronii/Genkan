package app.genkan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.words.LookupCounts
import app.genkan.words.WordSettings
import app.genkan.words.WordStatus
import app.genkan.words.WordStatusEntity
import app.genkan.words.WordStatuses
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WordsSettingsViewModel @Inject constructor(
    private val words: WordStatuses,
    private val counts: LookupCounts,
    val settings: WordSettings,
) : ViewModel() {

    val rows: StateFlow<List<WordStatusEntity>> = words.rows.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _paste = MutableStateFlow("")
    val paste: StateFlow<String> = _paste.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** What the last action did. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _counted = MutableStateFlow(0)
    /** How many words have a lookup count. */
    val counted: StateFlow<Int> = _counted.asStateFlow()

    init {
        viewModelScope.launch { _counted.value = counts.size() }
    }

    fun editPaste(text: String) {
        _paste.value = text
    }

    /** The pasted words, one per line (or split by spaces or commas), all marked known. */
    fun addPasted() {
        val list = _paste.value.split(SEPARATORS).filter { it.isNotBlank() }
        viewModelScope.launch {
            val added = words.addKnown(list)
            _message.value = "Marked $added new word${if (added == 1) "" else "s"} known" +
                if (added < list.size) " (${list.size - added} already were)" else ""
            _paste.value = ""
        }
    }

    fun remove(row: WordStatusEntity) {
        viewModelScope.launch { words.set(row.headword, null) }
    }

    fun toggleCounts() {
        viewModelScope.launch { settings.setCounts(!settings.countsOn()) }
    }

    fun resetCounts() {
        viewModelScope.launch {
            counts.reset()
            _counted.value = 0
            _message.value = "Lookup counts reset"
        }
    }

    fun label(status: WordStatus): String = if (status == WordStatus.KNOWN) "Known" else "Ignored"

    private companion object {
        val SEPARATORS = Regex("[\\s,、，;；]+")
    }
}
