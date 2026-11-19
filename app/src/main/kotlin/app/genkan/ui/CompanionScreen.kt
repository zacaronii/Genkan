package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Phase 0 placeholder for the bottom screen; the reader replaces it in Phase 2. */
@Composable
fun CompanionScreen(
    displayLabel: String,
    lastToneResult: String?,
    onPlayTestTone: () -> Unit,
    onRelease: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Genkan companion", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(displayLabel, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
        ActionRow("Play test tone", focused = false, onClick = onPlayTestTone)
        lastToneResult?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
        }
        ActionRow("Release bottom screen", focused = false, onClick = onRelease)
    }
}
