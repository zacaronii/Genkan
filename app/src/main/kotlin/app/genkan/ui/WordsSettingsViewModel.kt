package app.genkan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.words.HachidoriConfig
import app.genkan.words.HachidoriLink
import app.genkan.words.LookupCounts
import app.genkan.words.PopupPrefs
import app.genkan.words.PopupPrefsSource
import app.genkan.words.WordSettings
import app.genkan.words.WordStatus
import app.genkan.words.WordStatusEntity
import app.genkan.words.WordStatuses
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WordsSettingsViewModel @Inject constructor(
    private val words: WordStatuses,
    private val counts: LookupCounts,
    val settings: WordSettings,
    val hachidori: HachidoriLink,
    popupPrefs: PopupPrefsSource,
) : ViewModel() {

    /** What the popup uses now: these settings, or a linked Hachidori's. */
    val prefs: StateFlow<PopupPrefs> = popupPrefs.prefs

    fun toggleSummary() {
        viewModelScope.launch { settings.setSummary(!settings.summary.first()) }
    }

    /** One more brief definition, 1 to 6, then back to 1. */
    fun nextSummaryCount() {
        viewModelScope.launch { settings.setSummaryCount(settings.summaryCount.first() % 6 + 1) }
    }

    private val _link = MutableStateFlow(HachidoriConfig())
    /** The Hachidori form. */
    val link: StateFlow<HachidoriConfig> = _link.asStateFlow()

    fun editLink(change: (HachidoriConfig) -> HachidoriConfig) {
        _link.value = change(_link.value)
    }

    /** Saves the address and links (or unlinks, when it's switched off). */
    fun saveLink(enabled: Boolean) {
        val config = _link.value.copy(enabled = enabled)
        _link.value = config
        viewModelScope.launch {
            hachidori.save(config)
            _message.value = if (enabled) "Linking to Hachidori at ${config.url ?: "(no address)"}…" else "Hachidori unlinked"
        }
    }

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
        viewModelScope.launch { _link.value = hachidori.config.first() }
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
