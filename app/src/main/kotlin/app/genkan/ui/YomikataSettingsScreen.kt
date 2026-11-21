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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.memory.InboxStatus

@Composable
fun YomikataSettingsScreen(viewModel: YomikataSettingsViewModel) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val local by viewModel.local.collectAsStateWithLifecycle()
    val status by viewModel.inbox.status.collectAsStateWithLifecycle()
    val sampling by viewModel.playerSettings.samples.collectAsStateWithLifecycle(false)
    val colors = MaterialTheme.colorScheme
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Yomikata", style = MaterialTheme.typography.displaySmall, color = colors.primary)
        Text(
            "Memory samples help build character tables for games whose text isn't standard: when a built-in " +
                "player line isn't in memory as Shift-JIS or UTF-16, the places matching its shape are saved with " +
                "what OCR read. They hold script text, so they go only to your own Yomikata inbox, after each play " +
                "session; a copy stays in Download/Genkan/yomikata-samples. B: back",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionRow(
                "Save samples while playing: " + if (sampling) "on" else "off",
                focused = null,
                onClick = viewModel::toggleSamples,
                modifier = Modifier.focusRequester(firstField),
            )
            GenkanTextField(form.address, { v -> viewModel.edit { it.copy(address = v) } }, "Inbox address (host, or a full URL)")
            GenkanTextField(form.token, { v -> viewModel.edit { it.copy(token = v) } }, "Inbox token", keyboardType = KeyboardType.Password)
            ActionRow("Save and test", focused = null, onClick = viewModel::saveAndTest)
            ActionRow("Send all samples again", focused = null, onClick = viewModel::sendAll)
        }
        check?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground) }
        Text(
            "On this device: ${local.samples} samples from ${local.games} game${if (local.games == 1) "" else "s"}" +
                when (val s = status) {
                    InboxStatus.NotSet -> "  ·  no inbox set"
                    is InboxStatus.Unreachable -> "  ·  inbox unreachable (${s.reason})"
                    is InboxStatus.Refused -> "  ·  inbox refused: ${s.reason}"
                    is InboxStatus.Sent -> "  ·  just sent ${s.files} file${if (s.files == 1) "" else "s"}, ${s.added} new samples"
                    InboxStatus.Idle -> ""
                },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onBackground,
        )
    }
}
