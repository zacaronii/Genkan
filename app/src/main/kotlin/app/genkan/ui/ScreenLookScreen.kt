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
import app.genkan.core.PlayerSettings
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
    "snes" to "Super Famicom",
    "nds" to "Nintendo DS",
    "psx" to "PlayStation",
    "psp" to "PlayStation Portable",
)

@HiltViewModel
class ScreenLookViewModel @Inject constructor(
    private val looks: ScreenLooks,
    private val player: PlayerSettings,
) : ViewModel() {

    /** The consoles whose sound is played without asking for low latency. */
    val lowLatencyOff: StateFlow<Set<String>> =
        player.lowLatencyOff.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleLowLatency(platform: String) {
        viewModelScope.launch { player.setLowLatency(platform, platform in lowLatencyOff.value) }
    }

    /** The device's A is its right button. */
    val aOnRight: StateFlow<Boolean> = player.aOnRight.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleAOnRight() {
        viewModelScope.launch { player.setAOnRight(!aOnRight.value) }
    }

    /** The consoles whose A/B and X/Y are swapped. */
    val faceSwapped: StateFlow<Set<String>> = player.faceSwapped.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleFaceSwap(platform: String) {
        viewModelScope.launch { player.toggleFaceSwap(platform) }
    }


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
    val lowLatencyOff by viewModel.lowLatencyOff.collectAsStateWithLifecycle()
    val aOnRight by viewModel.aOnRight.collectAsStateWithLifecycle()
    val faceSwapped by viewModel.faceSwapped.collectAsStateWithLifecycle()
    // The view model is the activity's: a list left open (Start out of settings) mustn't greet the next visit.
    DisposableEffect(Unit) { onDispose { viewModel.closePicker() } }
    ScreenLookContent(
        consoles, picking, onOpen = viewModel::open, onPick = viewModel::pick, onClose = { viewModel.closePicker() },
        lowLatencyOff = lowLatencyOff, onToggleLowLatency = viewModel::toggleLowLatency,
        aOnRight = aOnRight, onToggleAOnRight = viewModel::toggleAOnRight,
        faceSwapped = faceSwapped, onToggleFaceSwap = viewModel::toggleFaceSwap,
    )
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
    /** The consoles whose sound is played without asking for low latency, and a press on one's row. */
    lowLatencyOff: Set<String> = emptySet(),
    onToggleLowLatency: (String) -> Unit = {},
    /** The device's A is its right button, and a press on that row. */
    aOnRight: Boolean = false,
    onToggleAOnRight: () -> Unit = {},
    /** The consoles whose A/B and X/Y are swapped, and a press on one's row. */
    faceSwapped: Set<String> = emptySet(),
    onToggleFaceSwap: (String) -> Unit = {},
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
        Text("Screen look and sound", style = MaterialTheme.typography.displaySmall, color = colors.primary)
        Text(
            "How the built-in player draws each console's picture on the top screen. A press opens the looks to pick from. " +
                "Until you choose, PlayStation, Super Famicom and DS are Sharp, the Game Boys LCD and the PSP Default. " +
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
        Text("Low-latency sound", style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
        Text(
            "Asks the device for its fastest sound stream, so sound keeps up with the picture. A device that can't give " +
                "one plays a plain stream instead. Switch it off for a console whose sound is missing or crackles.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LOOK_CONSOLES.forEach { (platform, name) ->
                ActionRow(
                    "$name: ${if (platform in lowLatencyOff) "off" else "on"}",
                    focused = null,
                    onClick = { onToggleLowLatency(platform) },
                )
            }
        }
        Text("Face buttons", style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
        Text(
            "The launcher follows your device's own button labels; the built-in player goes by position, giving each game " +
                "the buttons where its console's pad had them: the right one is A on Nintendo consoles and circle on " +
                "PlayStation, the bottom one B and cross. It assumes your device's A is at the bottom unless you switch it.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        ActionRow(
            if (aOnRight) "Face buttons: A on the right (Nintendo layout)" else "Face buttons: A at the bottom (Xbox layout)",
            focused = null,
            onClick = onToggleAOnRight,
        )
        Text(
            "Swap A/B and X/Y for one console only, on top of the layout above. Select + Start and the stick clicks are not changed.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LOOK_CONSOLES.forEach { (platform, name) ->
                ActionRow(
                    "$name: ${if (platform in faceSwapped) "swapped" else "as the device"}",
                    focused = null,
                    onClick = { onToggleFaceSwap(platform) },
                )
            }
        }
        HintRow(Hints.form())
    }
}
