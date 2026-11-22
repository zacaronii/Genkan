package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    "psp" to "PlayStation Portable",
)

@HiltViewModel
class ScreenLookViewModel @Inject constructor(private val looks: ScreenLooks) : ViewModel() {

    /** The looks chosen for consoles; a console that isn't in it has its default. */
    val consoles: StateFlow<Map<String, ScreenLook>> =
        looks.consoles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _picking = MutableStateFlow<String?>(null)
    /** The console whose look is being picked (its platform), or null with no list open. */
    val picking: StateFlow<String?> = _picking.asStateFlow()

    fun open(platform: String) {
        _picking.value = platform
    }

    fun pick(platform: String, look: ScreenLook) {
        _picking.value = null
        viewModelScope.launch { looks.setConsole(platform, look) }
    }

    /** Closes the list; false when none was open. */
    fun closePicker(): Boolean = _picking.value != null && true.also { _picking.value = null }
}

@Composable
fun ScreenLookScreen(viewModel: ScreenLookViewModel) {
    val consoles by viewModel.consoles.collectAsStateWithLifecycle()
    val picking by viewModel.picking.collectAsStateWithLifecycle()
    // The view model is the activity's: a list left open (Start out of settings) mustn't greet the next visit.
    DisposableEffect(Unit) { onDispose { viewModel.closePicker() } }
    ScreenLookContent(consoles, picking, onOpen = viewModel::open, onPick = viewModel::pick, onClose = { viewModel.closePicker() })
}

/**
 * The page drawn from plain state: a row per console, each pressed to pick its look from a list
 * ([picking]: the console whose list is open).
 */
@Composable
fun ScreenLookContent(
    consoles: Map<String, ScreenLook>,
    picking: String? = null,
    onOpen: (String) -> Unit = {},
    onPick: (String, ScreenLook) -> Unit = { _, _ -> },
    onClose: () -> Unit = {},
) {
    // The console whose list was opened last: the pad comes back to its row.
    var returnTo by remember { mutableStateOf<String?>(null) }
    if (picking != null) returnTo = picking
    picking?.let { platform ->
        val name = LOOK_CONSOLES.firstOrNull { it.first == platform }?.second ?: platform
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 32.dp, vertical = 16.dp)) {
            LookPicker(
                title = "$name's look",
                selected = consoles[platform] ?: ScreenLook.defaultFor(platform),
                onPick = { look -> look?.let { onPick(platform, it) } },
                onClose = onClose,
                suits = ScreenLook.suiting(platform),
                focusSelected = true,
            )
        }
        return
    }
    val colors = MaterialTheme.colorScheme
    val rows = remember { LOOK_CONSOLES.associate { it.first to FocusRequester() } }
    LaunchedEffect(Unit) { runCatching { rows.getValue(returnTo ?: LOOK_CONSOLES.first().first).requestFocus() } }
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
            "How the built-in player draws each console's picture on the top screen. A press opens the looks to pick from. " +
                "Until you choose, PlayStation and DS are Sharp, the Game Boys LCD and the PSP Default. " +
                "A game can have its own in its details, and the reader's Look chip tries others while playing; " +
                "Keep then makes the one on screen that game's own.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LOOK_CONSOLES.forEach { (platform, name) ->
                val look = consoles[platform] ?: ScreenLook.defaultFor(platform)
                ActionRow(
                    "$name: ${look.label}  ·  ${look.description}",
                    focused = null,
                    onClick = { onOpen(platform) },
                    modifier = Modifier.focusRequester(rows.getValue(platform)),
                )
            }
        }
        HintRow(Hints.form())
    }
}
