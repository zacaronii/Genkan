package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.home.DualScreenStatus

@Composable
fun SetupScreen(viewModel: SetupViewModel, onAction: (SetupAction) -> Unit) {
    val status by viewModel.dualScreen.status.collectAsStateWithLifecycle()
    val focusIndex by viewModel.focusIndex.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Genkan", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        StatusLines(status)
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            viewModel.actions.forEachIndexed { index, action ->
                ActionRow(action.label, focused = index == focusIndex, onClick = {
                    viewModel.focus(action)
                    onAction(action)
                })
            }
        }
    }
}

@Composable
private fun StatusLines(status: DualScreenStatus) {
    val lines = listOf(
        "Default home" to yesNo(status.isDefaultHome),
        "Home entry enabled" to yesNo(status.homeAliasEnabled),
        "Second screen" to (status.secondaryDisplayId?.let { "display $it" } ?: "not found"),
        "Companion" to when {
            !status.secondaryUsable -> "refused by the system (single-screen)"
            status.lowerDisplayId != null -> "running on display ${status.lowerDisplayId}"
            !status.companionEnabled -> "released"
            else -> "not running"
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { (label, value) ->
            Text("$label: $value", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

private fun yesNo(value: Boolean) = if (value) "yes" else "no"
