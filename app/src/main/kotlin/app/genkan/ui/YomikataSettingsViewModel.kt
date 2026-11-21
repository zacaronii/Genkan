package app.genkan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.core.PlayerSettings
import app.genkan.memory.InboxConfig
import app.genkan.memory.SampleInbox
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
) : ViewModel() {

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
                inbox.send()
                "Connected. The inbox holds samples for $games game${if (games == 1) "" else "s"}."
            } catch (e: Exception) {
                "Couldn't reach the inbox at ${config.endpoint}: ${e.message ?: e.javaClass.simpleName}. " +
                    "It answers on your own network only."
            }
            countLocal()
        }
    }

    fun sendAll() {
        viewModelScope.launch {
            inbox.send(all = true)
            countLocal()
        }
    }

    fun toggleSamples() {
        viewModelScope.launch { playerSettings.setSamples(!playerSettings.samplesEnabled()) }
    }
}
