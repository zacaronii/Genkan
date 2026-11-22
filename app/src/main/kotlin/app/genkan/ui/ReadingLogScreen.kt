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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
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
    // The pad needs somewhere to start: focus the first field.
    val firstField = remember { FocusRequester() }
    // The result lands below the button, where the pad can't scroll: bring it into view.
    val scroll = rememberScrollState()
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(status) { if (opened) scroll.animateScrollTo(scroll.maxValue) else opened = true }
    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(scroll)
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Reading log", style = MaterialTheme.typography.displaySmall, color = colors.primary)
        Text(
            "Genkan counts reading time and characters (kana and kanji, each line once) per game. " +
                "With a mochibridge address (and its API token, if it has one), each session is logged in kechimochi " +
                "with both.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        HintRow(Hints.form())
        Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GenkanTextField(form.address, { v -> viewModel.edit { it.copy(address = v) } }, "mochibridge address (host:port)", modifier = Modifier.focusRequester(firstField))
            GenkanTextField(form.token, { v -> viewModel.edit { it.copy(token = v) } }, "API token (web.api_token, if set)", keyboardType = KeyboardType.Password)
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
