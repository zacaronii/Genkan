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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.romm.RomMState

/**
 * RomM sign-in. A form, so unlike the library it uses Compose focus: the D-pad moves between
 * fields and A types through the on-screen keyboard.
 */
@Composable
fun LoginScreen(viewModel: LoginViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Connect to RomM", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val s = state) {
                is RomMState.AwaitingApproval -> AwaitingApproval(s, onCancel = viewModel::cancel)
                else -> {
                    GenkanTextField(
                        value = viewModel.server,
                        onValueChange = { viewModel.server = it },
                        label = "Server address",
                        modifier = Modifier.focusRequester(firstField),
                    )
                    if (viewModel.usePairingCode) {
                        GenkanTextField(
                            value = viewModel.pairingCode,
                            onValueChange = { viewModel.pairingCode = it },
                            label = "Pairing code from RomM (Client API tokens)",
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    ActionRow(
                        if (s is RomMState.Connecting) "Connecting…" else "Sign in",
                        focused = null,
                        onClick = viewModel::signIn,
                    )
                    ActionRow(
                        if (viewModel.usePairingCode) "Sign in with a code on the RomM website instead" else "Use a pairing code instead",
                        focused = null,
                        onClick = { viewModel.usePairingCode = !viewModel.usePairingCode },
                    )
                    if (s is RomMState.Failed) {
                        Text(s.message, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFFF8A80))
                    }
                    Text(
                        "Start opens Genkan's settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AwaitingApproval(state: RomMState.AwaitingApproval, onCancel: () -> Unit) {
    Text("On your phone or computer, open", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    Text(state.verificationUrl, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    Text("and approve this code:", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    Text(
        state.userCode.chunked(4).joinToString("-"),
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text("Waiting for approval…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ActionRow("Cancel", focused = null, onClick = onCancel)
}
