package app.genkan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.core.PlayerSettings
import app.genkan.memory.InboxConfig
import app.genkan.memory.SampleInbox
import app.genkan.yomikata.CatalogConfig
import app.genkan.yomikata.YomikataCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Samples on this device: how many games' files, and samples in all. */
data class LocalSamples(val games: Int = 0, val samples: Int = 0)

@HiltViewModel
class YomikataSettingsViewModel @Inject constructor(
    val inbox: SampleInbox,
    val playerSettings: PlayerSettings,
    val catalog: YomikataCatalog,
) : ViewModel() {

    private val _catalogForm = MutableStateFlow(CatalogConfig())
    val catalogForm: StateFlow<CatalogConfig> = _catalogForm.asStateFlow()

    private val _catalogCheck = MutableStateFlow<String?>(null)
    /** What the last catalog update found. */
    val catalogCheck: StateFlow<String?> = _catalogCheck.asStateFlow()

    private val _form = MutableStateFlow(InboxConfig())
    val form: StateFlow<InboxConfig> = _form.asStateFlow()

    private val _check = MutableStateFlow<String?>(null)
    /** What the last Save and test found. */
    val check: StateFlow<String?> = _check.asStateFlow()

    private val _local = MutableStateFlow(LocalSamples())
    val local: StateFlow<LocalSamples> = _local.asStateFlow()

    init {
        viewModelScope.launch {
            _form.value = inbox.config.first()
            _catalogForm.value = catalog.config.first()
            countLocal()
        }
    }

    private suspend fun countLocal() {
        val files = inbox.local()
        _local.value = LocalSamples(files.size, files.values.sum())
    }

    fun edit(change: (InboxConfig) -> InboxConfig) {
        _form.value = change(_form.value)
    }

    /** Saves the form, asks the inbox what it holds, then sends anything new. */
    fun saveAndTest() {
        val config = _form.value
        viewModelScope.launch {
            inbox.save(config)
            if (!config.configured) {
                _check.value = "Saved. Samples stay on this device until an inbox address and token are set."
                return@launch
            }
            _check.value = "Checking ${config.endpoint}…"
            _check.value = try {
                val games = inbox.check(config)
                inbox.send(retryHeld = true)
                "Connected. The inbox holds samples for $games game${if (games == 1) "" else "s"}."
            } catch (e: Exception) {
                "Couldn't reach the inbox at ${config.endpoint}: ${e.message ?: e.javaClass.simpleName}. " +
                    "It answers on your own network only."
            }
            countLocal()
        }
    }

    fun editCatalog(change: (CatalogConfig) -> CatalogConfig) {
        _catalogForm.value = change(_catalogForm.value)
    }

    /** Saves the catalog's repository and token, then fetches its index. */
    fun saveAndUpdateCatalog() {
        val config = _catalogForm.value
        viewModelScope.launch {
            catalog.save(config)
            if (!config.configured) {
                _catalogCheck.value = "Saved. Set the repository as owner/name to use the catalog."
                return@launch
            }
            _catalogCheck.value = "Fetching the catalog…"
            val count = catalog.refresh(force = true)
            _catalogCheck.value = if (count != null) {
                "The catalog lists $count releases. Entries are fetched when their game starts."
            } else {
                "Couldn't fetch the catalog; see the status below."
            }
        }
    }

    fun sendAll() {
        viewModelScope.launch {
            inbox.send(all = true, retryHeld = true)
            countLocal()
        }
    }

    fun toggleSamples() {
        viewModelScope.launch { playerSettings.setSamples(!playerSettings.samplesEnabled()) }
    }
}
