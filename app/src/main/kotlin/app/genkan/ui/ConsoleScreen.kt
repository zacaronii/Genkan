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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.core.PlayerChoice
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

/** Every console the built-in player can run, by platform slug, in the order the page lists them. */
private val ALL_LOOK_CONSOLES: List<Pair<String, String>> = listOf(
    "gb" to "Game Boy",
    "gbc" to "Game Boy Color",
    "gba" to "Game Boy Advance",
    "nes" to "Famicom",
    "fds" to "Famicom Disk System",
    "snes" to "Super Famicom",
    "genesis" to "Mega Drive",
    "tg16" to "PC Engine",
    "turbografx-cd" to "PC Engine CD-ROM²",
    "nds" to "Nintendo DS",
    "psx" to "PlayStation",
    "saturn" to "Saturn",
    "n64" to "Nintendo 64",
    "dc" to "Dreamcast",
    "ps2" to "PlayStation 2",
    "ngc" to "GameCube",
    "wii" to "Wii",
    "psp" to "PlayStation Portable",
)

/** The consoles this build's built-in player runs. */
val LOOK_CONSOLES: List<Pair<String, String>> = ALL_LOOK_CONSOLES.filter { it.first in app.genkan.core.CoreCatalog.platforms }

/**
 * One console's page of settings (Settings › Consoles): its look, its sound, its face buttons, what
 * plays it where there's a choice, and where its text comes from. The settings are kept where
 * they always were (ScreenLooks, PlayerSettings); only the page is new.
 */
@HiltViewModel
class ConsoleViewModel @Inject constructor(
    private val looks: ScreenLooks,
    private val player: PlayerSettings,
) : ViewModel() {

    private val _console = MutableStateFlow(LOOK_CONSOLES.first().first)
    /** The console the page shows, by platform slug. */
    val console: StateFlow<String> = _console.asStateFlow()

    /** Shows [platform]'s page, with no list open. */
    fun show(platform: String) {
        _console.value = platform
        _picking.value = false
    }

    /** The consoles whose sound is played without asking for low latency. */
    val lowLatencyOff: StateFlow<Set<String>> =
        player.lowLatencyOff.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleLowLatency() {
        val platform = console.value
        viewModelScope.launch { player.setLowLatency(platform, platform in lowLatencyOff.value) }
    }

    /** The consoles whose A/B and X/Y are swapped. */
    val faceSwapped: StateFlow<Set<String>> = player.faceSwapped.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleFaceSwap() {
        val platform = console.value
        viewModelScope.launch { player.toggleFaceSwap(platform) }
    }

    /** What plays each console that has a choice (PlayerChoice): a core's id, or the emulator app. */
    val coreChoice: StateFlow<Map<String, String>> = player.coreChoice.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Moves the console to its next way of playing. */
    fun cycleCore() {
        val platform = console.value
        val next = PlayerChoice.next(platform, coreChoice.value) ?: return
        viewModelScope.launch { player.setCoreChoice(platform, next.value) }
    }

    /** The looks chosen for consoles; a console that isn't in it has its default. */
    val consoles: StateFlow<Map<String, ScreenLook>> =
        looks.consoles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _picking = MutableStateFlow(false)
    /** Whether the console's list of looks is open. */
    val picking: StateFlow<Boolean> = _picking.asStateFlow()

    fun openLooks() {
        _picking.value = true
    }

    fun pick(look: ScreenLook) {
        val platform = console.value
        _picking.value = false
        viewModelScope.launch { looks.setConsole(platform, look) }
    }

    /** Closes the list; false when none was open. */
    fun closePicker(): Boolean = _picking.value && true.also { _picking.value = false }
}

/** Where a console's text comes from, on its page: from its emulator's memory when [on], else OCR. */
data class ConsoleText(val on: Boolean, val from: String, val action: SetupAction)

/** The text source of a console that has one ([state]: the switches as Settings shows them). */
fun consoleText(platform: String, state: SetupState): ConsoleText? = when (platform) {
    "psp" -> ConsoleText(state.pspDebugger, "PPSSPP's memory (its debugger is open to your network)", SetupAction.PSP_DEBUGGER)
    "ps2" -> ConsoleText(state.armsxPine, "ARMSX2's memory (PINE, switched on in its Advanced settings)", SetupAction.ARMSX2_PINE)
    else -> null
}

@Composable
fun ConsoleScreen(viewModel: ConsoleViewModel, state: SetupState, onAction: (SetupAction) -> Unit) {
    val platform by viewModel.console.collectAsStateWithLifecycle()
    val consoles by viewModel.consoles.collectAsStateWithLifecycle()
    val picking by viewModel.picking.collectAsStateWithLifecycle()
    val lowLatencyOff by viewModel.lowLatencyOff.collectAsStateWithLifecycle()
    val faceSwapped by viewModel.faceSwapped.collectAsStateWithLifecycle()
    val coreChoice by viewModel.coreChoice.collectAsStateWithLifecycle()
    // The view model is the activity's: a list left open (Start out of settings) mustn't greet the next visit.
    DisposableEffect(Unit) { onDispose { viewModel.closePicker() } }
    ConsoleContent(
        platform = platform,
        look = consoles[platform] ?: ScreenLook.defaultFor(platform),
        picking = picking, onOpenLooks = viewModel::openLooks, onPick = viewModel::pick, onClose = { viewModel.closePicker() },
        lowLatency = platform !in lowLatencyOff, onToggleLowLatency = viewModel::toggleLowLatency,
        faceSwapped = platform in faceSwapped, onToggleFaceSwap = viewModel::toggleFaceSwap,
        playsWith = PlayerChoice.current(platform, coreChoice).takeIf { PlayerChoice.options(platform).size > 1 },
        onCyclePlaysWith = viewModel::cycleCore,
        text = consoleText(platform, state), onAction = onAction,
    )
}

/** The page drawn from plain state: a row per setting, the look's row opening its list ([picking]). */
@Composable
fun ConsoleContent(
    platform: String,
    look: ScreenLook,
    picking: Boolean = false,
    onOpenLooks: () -> Unit = {},
    onPick: (ScreenLook) -> Unit = {},
    onClose: () -> Unit = {},
    lowLatency: Boolean = true,
    onToggleLowLatency: () -> Unit = {},
    faceSwapped: Boolean = false,
    onToggleFaceSwap: () -> Unit = {},
    /** What plays the console, or null for one with no choice; a press moves to the next. */
    playsWith: PlayerChoice.Option? = null,
    onCyclePlaysWith: () -> Unit = {},
    /** Where the console's text comes from, or null for one read only by OCR or its script. */
    text: ConsoleText? = null,
    onAction: (SetupAction) -> Unit = {},
) {
    val name = consoleName(platform)
    if (picking) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 32.dp, vertical = 16.dp)) {
            LookPicker(
                title = "$name's look",
                selected = look,
                onPick = { picked -> picked?.let(onPick) },
                onClose = onClose,
                suits = ScreenLook.suiting(platform),
                focusSelected = true,
            )
        }
        return
    }
    val colors = MaterialTheme.colorScheme
    // The pad starts on the look's row, and comes back to it when the list closes (drawn anew).
    val lookRow = remember { FocusRequester() }
    LaunchedEffect(platform) { runCatching { lookRow.requestFocus() } }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(name, style = MaterialTheme.typography.displaySmall, color = colors.primary)
        Text(
            "In the built-in player. A game can have a look of its own in its details, and the reader's Look chip tries others while playing.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionRow("Look: ${look.label}  ·  ${look.description}", focused = null, onClick = onOpenLooks, modifier = Modifier.focusRequester(lookRow))
            ActionRow("Low-latency sound: ${if (lowLatency) "on" else "off (for sound that's missing or crackles)"}", focused = null, onClick = onToggleLowLatency)
            ActionRow("Swap A/B and X/Y: ${if (faceSwapped) "swapped" else "as the device"}", focused = null, onClick = onToggleFaceSwap)
            playsWith?.let { ActionRow("Plays with: ${it.label}", focused = null, onClick = onCyclePlaysWith) }
            text?.let { ActionRow("Text: ${if (it.on) it.from else "OCR"}", focused = null, onClick = { onAction(it.action) }) }
        }
        HintRow(Hints.form())
    }
}

/** A console's name as Settings lists it, by platform slug. */
fun consoleName(platform: String): String = ALL_LOOK_CONSOLES.firstOrNull { it.first == platform }?.second ?: platform
