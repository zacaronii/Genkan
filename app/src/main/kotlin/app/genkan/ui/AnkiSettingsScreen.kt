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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.anki.AnkiStatus

@Composable
fun AnkiSettingsScreen(viewModel: AnkiSettingsViewModel) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val status by viewModel.miner.status.collectAsStateWithLifecycle()
    val pending by viewModel.miner.pending.collectAsStateWithLifecycle(0)
    val refused by viewModel.miner.refused.collectAsStateWithLifecycle(emptyList())
    val colors = MaterialTheme.colorScheme
    // The pad needs somewhere to start: focus the first field.
    val firstField = remember { FocusRequester() }
    // The result lands below the button, where the pad can't scroll: bring it into view.
    val scroll = rememberScrollState()
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(check) { if (opened) scroll.animateScrollTo(scroll.maxValue) else opened = true }
    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(scroll)
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Anki", style = MaterialTheme.typography.displaySmall, color = colors.primary)
        Text(
            "Cards go to an AnkiConnect server (desktop Anki with the AnkiConnect add-on, listening on " +
                "0.0.0.0). Cards made offline wait here and are sent when it answers. B: back",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GenkanTextField(form.address, { v -> viewModel.edit { it.copy(address = v) } }, "AnkiConnect address (host or host:port)", modifier = Modifier.focusRequester(firstField))
            GenkanTextField(form.apiKey, { v -> viewModel.edit { it.copy(apiKey = v) } }, "API key (if AnkiConnect has one)", keyboardType = KeyboardType.Password)
            GenkanTextField(form.deck, { v -> viewModel.edit { it.copy(deck = v) } }, "Deck", keyboardType = KeyboardType.Text)
            GenkanTextField(form.noteType, { v -> viewModel.edit { it.copy(noteType = v) } }, "Note type (Lapis or Kiku)", keyboardType = KeyboardType.Text)
            ActionRow("Save and test", focused = null, onClick = viewModel::saveAndTest)
        }
        check?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground) }
        Text(
            "Outbox: $pending waiting" + when (val s = status) {
                AnkiStatus.NotSet -> "  ·  no address set"
                is AnkiStatus.Unreachable -> "  ·  Anki unreachable (${s.reason})"
                is AnkiStatus.Sent -> "  ·  just sent ${s.count}"
                AnkiStatus.Idle -> ""
            },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onBackground,
        )
        refused.take(5).forEach {
            Text("Refused: ${it.expression}: ${it.error}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}
