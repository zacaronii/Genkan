package app.genkan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.words.HachidoriConfig
import app.genkan.words.HachidoriImports
import app.genkan.words.HachidoriLink
import app.genkan.words.HostDictionary
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
    val imports: HachidoriImports,
) : ViewModel() {

    private val _hostDictionaries = MutableStateFlow<List<HostDictionary>?>(null)
    /** The linked host's dictionaries, once asked for. */
    val hostDictionaries: StateFlow<List<HostDictionary>?> = _hostDictionaries.asStateFlow()

    fun loadHostDictionaries() {
        viewModelScope.launch {
            _hostDictionaries.value = try {
                imports.hostDictionaries()
            } catch (e: Exception) {
                _message.value = "Couldn't list Hachidori's dictionaries: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }
    }

    fun getDictionary(dictionary: HostDictionary) {
        if (dictionary.installed) return
        viewModelScope.launch {
            imports.get(dictionary)
            _hostDictionaries.value = runCatching { imports.hostDictionaries() }.getOrNull() ?: _hostDictionaries.value
        }
    }

    /** Imports the newest Hachidori backup in Download. */
    fun importBackup() {
        viewModelScope.launch {
            val file = imports.findBackup()
            if (file == null) {
                _message.value = "No Hachidori backup in Download (a .zip with \"hachidori\" in its name)"
                return@launch
            }
            imports.importBackup(file)
            _counted.value = counts.size()
        }
    }

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
