package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.stats.ReadingLogConfig
import app.genkan.stats.ReadingLogPusher
import app.genkan.stats.ReadingLogSettings
import app.genkan.stats.ReadingLogStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReadingLogViewModel @Inject constructor(
    private val settings: ReadingLogSettings,
    val pusher: ReadingLogPusher,
) : ViewModel() {

    private val _form = MutableStateFlow(ReadingLogConfig())
    val form: StateFlow<ReadingLogConfig> = _form.asStateFlow()

    init {
        viewModelScope.launch { _form.value = settings.config.first() }
    }

    fun edit(change: (ReadingLogConfig) -> ReadingLogConfig) {
        _form.value = change(_form.value)
    }

    fun saveAndSend() {
        viewModelScope.launch {
            settings.save(_form.value)
            pusher.push()
        }
    }
}

@Composable
fun ReadingLogScreen(viewModel: ReadingLogViewModel) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val status by viewModel.pusher.status.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Reading log", style = MaterialTheme.typography.displaySmall, color = colors.primary)
        Text(
            "Genkan counts reading time and characters (kana and kanji, each line once) per game. " +
                "With a mochibridge address and its API token, each session is logged in kechimochi " +
                "with both. B: back",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GenkanTextField(form.address, { v -> viewModel.edit { it.copy(address = v) } }, "mochibridge address (host:port)")
            GenkanTextField(form.token, { v -> viewModel.edit { it.copy(token = v) } }, "API token (web.api_token)", keyboardType = KeyboardType.Password)
            ActionRow("Save and send now", focused = null, onClick = viewModel::saveAndSend)
        }
        Text(
            when (val s = status) {
                ReadingLogStatus.NotSet -> "Not set: sessions are kept on this device."
                ReadingLogStatus.UpToDate -> "Up to date."
                is ReadingLogStatus.Sent -> "Sent ${s.sessions} sessions."
                is ReadingLogStatus.Failed -> "Couldn't send: ${s.reason}. Sessions wait and are sent later."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onBackground,
        )
    }
}
