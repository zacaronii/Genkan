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
import app.genkan.privileged.Privileged
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.home.DualScreenStatus
import app.genkan.library.SyncStatus
import app.genkan.romm.RomMState

@Composable
fun SetupScreen(viewModel: SetupViewModel, onAction: (SetupAction) -> Unit) {
    val status by viewModel.dualScreen.status.collectAsStateWithLifecycle()
    val focusIndex by viewModel.focusIndex.collectAsStateWithLifecycle()
    val romm by viewModel.romm.state.collectAsStateWithLifecycle()
    val sync by viewModel.librarySync.status.collectAsStateWithLifecycle()
    val usageAccess by viewModel.usageAccess.collectAsStateWithLifecycle()
    val shizuku by viewModel.shizuku.collectAsStateWithLifecycle()
    val pspDebugger by viewModel.ppssppText.enabled.collectAsStateWithLifecycle(false)
    val azaharRpc by viewModel.azaharText.enabled.collectAsStateWithLifecycle(false)
    val dictionaries by viewModel.dictionaries.all.collectAsStateWithLifecycle(emptyList())

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Genkan settings", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        StatusLines(status)
        Text(
            "RomM: " + when (val r = romm) {
                is RomMState.SignedIn -> "${r.account.username} on ${r.account.baseUrl.removeSuffix("/")} (RomM ${r.account.serverVersion})"
                else -> "not signed in"
            } + "  ·  Library: " + when (val s = sync) {
                is SyncStatus.Done -> "${s.games} games"
                is SyncStatus.Running -> "syncing ${s.platform}…"
                is SyncStatus.Failed -> "sync failed (${s.message})"
                SyncStatus.Idle -> "not synced"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "Play time: " + if (usageAccess) "exact (usage access on)" else "approximate; allow usage access so sleep and other apps aren't counted",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "Emulator saves (DuckStation, Dolphin): " + when (shizuku) {
                Privileged.State.READY -> "reachable through Shizuku"
                Privileged.State.NOT_ALLOWED -> "Shizuku is running; allow Genkan below"
                Privileged.State.STOPPED -> "start Shizuku (it stops at every reboot)"
                Privileged.State.MISSING -> "install Shizuku to sync them"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "PSP text from PPSSPP: " + if (pspDebugger) {
                "on; PPSSPP's debugger is reachable from your network while a game runs"
            } else {
                "off; PSP games use OCR (switching it on opens PPSSPP's debugger to your network while a game runs)"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "3DS text from Azahar: " + if (azaharRpc) {
                "on; after OCR reads a line, Genkan follows it in memory. Azahar's RPC is reachable from your network while a game runs"
            } else {
                "off; 3DS games use OCR (switching it on opens Azahar's RPC server to your network while a game runs)"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "Dictionaries: " + if (dictionaries.isEmpty()) "none imported" else
                "${dictionaries.count { it.enabled }} of ${dictionaries.size} on (${dictionaries.filter { it.enabled }.joinToString { it.title }})",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("B: back", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        "Second screen" to (status.secondaryDisplayId?.let { "display $it" } ?: "not found, or switched off"),
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
