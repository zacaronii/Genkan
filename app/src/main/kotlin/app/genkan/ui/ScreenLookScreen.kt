package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.core.ScreenLook
import app.genkan.core.ScreenLooks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The consoles the built-in player runs, by platform slug, in the order the page lists them. */
val LOOK_CONSOLES: List<Pair<String, String>> = listOf(
    "gb" to "Game Boy",
    "gbc" to "Game Boy Color",
    "gba" to "Game Boy Advance",
    "nds" to "Nintendo DS",
    "psx" to "PlayStation",
    "psp" to "PSP",
)

@HiltViewModel
class ScreenLookViewModel @Inject constructor(private val looks: ScreenLooks) : ViewModel() {

    /** The looks chosen for consoles; a console that isn't in it has its default. */
    val consoles: StateFlow<Map<String, ScreenLook>> =
        looks.consoles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** The console's next look. */
    fun step(platform: String) {
        val now = consoles.value[platform] ?: ScreenLook.defaultFor(platform)
        viewModelScope.launch { looks.setConsole(platform, now.next()) }
    }
}

@Composable
fun ScreenLookScreen(viewModel: ScreenLookViewModel) {
    val consoles by viewModel.consoles.collectAsStateWithLifecycle()
    ScreenLookContent(consoles, onStep = viewModel::step)
}

/** The page drawn from plain state: a row per console, each pressed to step to its next look. */
@Composable
fun ScreenLookContent(consoles: Map<String, ScreenLook>, onStep: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Screen look", style = MaterialTheme.typography.displaySmall, color = colors.primary)
        Text(
            "How the built-in player draws each console's picture on the top screen. A press steps to the next look. " +
                "Until you choose, PlayStation and DS are Sharp, the Game Boys LCD and the PSP Default. " +
                "A game can have its own in its details, and the reader's Look chip tries others while playing; " +
                "Keep then makes the one on screen that game's own.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LOOK_CONSOLES.forEachIndexed { index, (platform, name) ->
                val look = consoles[platform] ?: ScreenLook.defaultFor(platform)
                ActionRow(
                    "$name: ${look.label}  ·  ${look.description}",
                    focused = null,
                    onClick = { onStep(platform) },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        Text("The looks", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ScreenLook.entries.forEach { look ->
                Text(
                    "${look.label}: ${look.description}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        HintRow(Hints.form())
    }
}
