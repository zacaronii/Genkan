package app.genkan.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.bluetooth.ConnectRoute
import app.genkan.bluetooth.Headphones
import app.genkan.privileged.Privileged
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HeadphonesViewModel @Inject constructor(
    val headphones: Headphones,
    private val privileged: Privileged,
) : ViewModel() {
    private val _allowed = MutableStateFlow(headphones.allowed())
    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()

    private val _shizuku = MutableStateFlow(false)
    /** Shizuku is running and Genkan allowed: a tap connects outright. */
    val shizuku: StateFlow<Boolean> = _shizuku.asStateFlow()

    /** The list opened, or the permission came back: read the devices again. */
    fun refresh() {
        _allowed.value = headphones.allowed()
        _shizuku.value = privileged.state() == Privileged.State.READY
        viewModelScope.launch { headphones.refresh() }
    }

    /** [displayId]: the screen the list is on, where Bluetooth settings open if it comes to that. */
    fun connect(device: Headphones.Device, displayId: Int?) {
        viewModelScope.launch { headphones.connect(device, displayId) }
    }

    /** A list opened: no word left from the last one, and its rows follow Bluetooth while it's up. */
    fun opened() {
        headphones.clearStatus()
        headphones.watch()
        refresh()
    }

    fun closed() {
        headphones.stopWatching()
        headphones.clearStatus()
    }
}

/** The headphones list where it's shown (the reader's bottom screen, or the settings page). */
@Composable
fun HeadphonesPanel(viewModel: HeadphonesViewModel, modifier: Modifier = Modifier) {
    val allowed by viewModel.allowed.collectAsStateWithLifecycle()
    val shizuku by viewModel.shizuku.collectAsStateWithLifecycle()
    val devices by viewModel.headphones.devices.collectAsStateWithLifecycle()
    val status by viewModel.headphones.status.collectAsStateWithLifecycle()
    val display = androidx.compose.ui.platform.LocalView.current.display?.displayId
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }
    androidx.compose.runtime.DisposableEffect(Unit) {
        viewModel.opened()
        onDispose { viewModel.closed() }
    }
    HeadphonesList(
        allowed = allowed,
        shizuku = shizuku,
        devices = devices,
        status = status,
        onAllow = { ask.launch(Manifest.permission.BLUETOOTH_CONNECT) },
        onPick = { viewModel.connect(it, display) },
        modifier = modifier,
    )
}

/** The settings page: the list on its own, the pad moving through it. */
@Composable
fun HeadphonesScreen(viewModel: HeadphonesViewModel) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(48.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        HeadphonesPanel(viewModel, Modifier.widthIn(max = 720.dp))
    }
}

/** What the list says under its title. */
internal fun headphonesStatus(status: Headphones.Status, shizuku: Boolean): String = when (status) {
    Headphones.Status.Idle -> if (shizuku) "Tap headphones to connect them." else "Tap headphones to open them in Bluetooth settings. With Shizuku running, a tap connects them here."
    is Headphones.Status.Connecting -> "Connecting ${status.name}…"
    is Headphones.Status.Connected -> "${status.name} connected"
    is Headphones.Status.Opened -> when (status.where) {
        ConnectRoute.Step.OUTPUT_PICKER -> "Pick ${status.name} in the output list"
        else -> "Tap ${status.name} in Bluetooth settings"
    }
    is Headphones.Status.Failed -> "Couldn't connect ${status.name}"
}

/**
 * The bonded headphones, the connected ones first, and what the last tap did. Without the
 * Bluetooth permission there's one row, to allow it.
 */
@Composable
internal fun HeadphonesList(
    allowed: Boolean,
    shizuku: Boolean,
    devices: List<Headphones.Device>,
    status: Headphones.Status,
    onAllow: () -> Unit,
    onPick: (Headphones.Device) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val first = remember { FocusRequester() }
    Column(
        modifier
            .background(colors.surface, RoundedCornerShape(12.dp))
            .border(1.dp, colors.onSurface.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Bluetooth headphones", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        Text(
            if (allowed) headphonesStatus(status, shizuku) else "Genkan needs to see your Bluetooth devices to list them.",
            style = MaterialTheme.typography.bodySmall,
            color = if (status is Headphones.Status.Failed) colors.error else colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        when {
            !allowed -> HeadphonesRow("Allow", null, Modifier.focusRequester(first), onClick = onAllow)
            devices.isEmpty() -> Text("No Bluetooth headphones are paired.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            else -> devices.forEachIndexed { i, device ->
                HeadphonesRow(
                    device.name,
                    if (device.connected) "Connected" else null,
                    if (i == 0) Modifier.focusRequester(first) else Modifier,
                ) { onPick(device) }
            }
        }
    }
    LaunchedEffect(allowed, devices.isEmpty()) { runCatching { first.requestFocus() } }
}

@Composable
private fun HeadphonesRow(label: String, state: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .border(2.dp, if (focused) colors.primary else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 1)
        state?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = colors.primary) }
    }
}
