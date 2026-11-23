package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.dictionary.DictionaryEntity
import app.genkan.home.DualScreenStatus
import app.genkan.library.SyncStatus
import app.genkan.privileged.Privileged
import app.genkan.romm.RomMState

@Composable
fun SetupScreen(viewModel: SetupViewModel, onAction: (SetupAction) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus by viewModel.focus.collectAsStateWithLifecycle()
    SetupContent(
        state = state,
        groups = viewModel.groups,
        focus = focus,
        onSelectGroup = viewModel::selectGroup,
        onRow = { group, row, action ->
            viewModel.focusRow(group, row)
            onAction(action)
        },
    )
}

/** Everything the settings page reports, gathered from the ViewModel's sources. */
data class SetupState(
    val status: DualScreenStatus,
    val romm: RomMState,
    val sync: SyncStatus,
    val usageAccess: Boolean,
    val shizuku: Privileged.State,
    val pspDebugger: Boolean,
    val azaharRpc: Boolean,
    val builtInPlayer: Boolean,
    val textSamples: Boolean,
    val dictionaries: List<DictionaryEntity>,
    val insertAnimation: Boolean = true,
    val fullBox: Boolean = true,
    val prefetchManuals: Boolean = true,
    val faceAOnRight: Boolean = false,
) {
    /** Whether the switch for [key] shows on. */
    fun isOn(key: SwitchKey): Boolean = when (key) {
        SwitchKey.HOME -> homeOn(status)
        SwitchKey.BUILT_IN_PLAYER -> builtInPlayer
        SwitchKey.INSERT_ANIMATION -> insertAnimation
        SwitchKey.FULL_BOX -> fullBox
        SwitchKey.PREFETCH_MANUALS -> prefetchManuals
        SwitchKey.FACE_A_ON_RIGHT -> faceAOnRight
        SwitchKey.PSP_TEXT -> pspDebugger
        SwitchKey.AZAHAR_TEXT -> azaharRpc
    }

    /**
     * What pressing [row] runs now: a switch runs its off action while it shows on. The pad (in
     * SetupViewModel) and a tap both ask here, so a switch with two actions can't differ by input.
     */
    fun actionFor(row: SettingsRow): SetupAction = when (row) {
        is SettingsRow.Button -> row.action
        is SettingsRow.Switch -> row.action(isOn(row.key))
    }

    companion object {
        /**
         * Home counts as on once Genkan is the default Home app; an enabled Home entry alone
         * still leaves another launcher answering Home.
         */
        fun homeOn(status: DualScreenStatus): Boolean = status.isDefaultHome
    }
}

/**
 * The settings page drawn from plain state, so it can be shown without a ViewModel: [groups] on
 * the left, the focused group's rows on the right. [focus] is where the gamepad is. A tap on a
 * group calls [onSelectGroup]; a tap on a row calls [onRow] with its place and the action it
 * runs now.
 */
@Composable
fun SetupContent(
    state: SetupState,
    groups: List<SettingsGroup>,
    focus: SettingsFocus,
    onSelectGroup: (Int) -> Unit,
    onRow: (group: Int, row: Int, action: SetupAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(24.dp),
    ) {
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(220.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = colors.primary)
            groups.forEachIndexed { index, group ->
                GroupItem(
                    title = group.title,
                    selected = index == focus.group,
                    listFocused = focus.pane == SettingsPane.GROUPS,
                    onClick = { onSelectGroup(index) },
                )
            }
        }
        val group = groups.getOrNull(focus.group) ?: return@Row
        // Keyed on the group so each one opens scrolled to its top.
        key(focus.group) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(group.title, style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
                group.info.forEach { info ->
                    Text(infoLine(info, state), style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                }
                group.rows.forEachIndexed { index, row ->
                    val on = (row as? SettingsRow.Switch)?.let { state.isOn(it.key) }
                    SettingRow(
                        title = row.title,
                        explanation = explanation(row, state),
                        status = status(row, state),
                        on = on,
                        focused = focus.pane == SettingsPane.ROWS && index == focus.row,
                        onClick = { onRow(focus.group, index, state.actionFor(row)) },
                    )
                }
            }
        }
    }
    val focusedRow = groups.getOrNull(focus.group)?.rows?.getOrNull(focus.row)
    HintRow(
        Hints.settings(
            onRows = focus.pane == SettingsPane.ROWS,
            rowAction = focusedRow?.let { Hints.settingsRow(it.title, (it as? SettingsRow.Switch)?.let { s -> state.isOn(s.key) }) },
        ),
        Modifier.padding(top = 12.dp),
    )
    }
}

@Composable
private fun GroupItem(title: String, selected: Boolean, listFocused: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.primary.copy(alpha = if (listFocused) 0.25f else 0.12f) else colors.surface, shape)
            .border(2.dp, if (selected && listFocused) colors.primary else Color.Transparent, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A row styled like [ActionRow], with an explanation under its title and, on the right, a short status and a switch. */
@Composable
private fun SettingRow(
    title: String,
    explanation: String,
    status: Status?,
    on: Boolean?,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    // The pad never moves Compose focus here, so the scrolling column follows the focused row by hand.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) {
        if (focused) bringIntoView.bringIntoView()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .background(if (focused) colors.primary.copy(alpha = 0.2f) else colors.surface, shape)
            .border(2.dp, if (focused) colors.primary else Color.Transparent, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(explanation, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        status?.let {
            Text(
                it.text,
                style = MaterialTheme.typography.titleSmall,
                color = if (it.attention) colors.error else colors.onSurfaceVariant,
            )
        }
        on?.let { SwitchMark(it) }
    }
}

/** A switch drawn for show: the row is what the pad and touch press. */
@Composable
private fun SwitchMark(on: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(width = 44.dp, height = 24.dp)
            .background(if (on) colors.primary else colors.onSurface.copy(alpha = 0.25f), CircleShape)
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(18.dp).background(if (on) colors.background else colors.onSurface, CircleShape))
    }
}

/** A short status at the right of a row; [attention] marks one that needs doing something. */
private data class Status(val text: String, val attention: Boolean = false)

private fun infoLine(info: SettingsInfo, state: SetupState): String = when (info) {
    SettingsInfo.ROMM_ACCOUNT -> when (val r = state.romm) {
        is RomMState.SignedIn ->
            "Signed in as ${r.account.username} on ${r.account.baseUrl.removeSuffix("/")} (RomM ${r.account.serverVersion})"
        else -> "Not signed in to RomM"
    }
    SettingsInfo.YOMIKATA_SAMPLES -> "Yomikata samples: " + if (state.textSamples) {
        "on; built-in player lines that aren't in memory as standard text are saved with what OCR read, for your Yomikata inbox"
    } else {
        "off (switched on in Yomikata)"
    }
    SettingsInfo.SECOND_SCREEN -> "Second screen: " + (state.status.secondaryDisplayId?.let { "display $it" } ?: "not found, or switched off")
    SettingsInfo.COMPANION -> "Companion: " + with(state.status) {
        when {
            !secondaryUsable -> "refused by the system (single-screen)"
            lowerDisplayId != null -> "running on display $lowerDisplayId"
            !companionEnabled -> "released"
            else -> "not running"
        }
    }
}

/** The line under a row's title: what it does, or what its state means. */
private fun explanation(row: SettingsRow, state: SetupState): String = when (row) {
    is SettingsRow.Switch -> switchExplanation(row.key, state)
    is SettingsRow.Button -> when (row.action) {
        SetupAction.SYNC_LIBRARY -> "Library: " + when (val s = state.sync) {
            is SyncStatus.Done -> "${s.games} games"
            is SyncStatus.Running -> "syncing ${s.platform}…"
            is SyncStatus.Failed -> "sync failed (${s.message})"
            SyncStatus.Idle -> "not synced"
        }
        SetupAction.SIGN_OUT -> "Forgets the RomM account on this device."
        SetupAction.SHIZUKU -> "DuckStation and Dolphin saves: " + when (state.shizuku) {
            Privileged.State.READY -> "reachable through Shizuku"
            Privileged.State.NOT_ALLOWED -> "Shizuku is running; press to allow Genkan"
            Privileged.State.STOPPED -> "start Shizuku (it stops at every reboot)"
            Privileged.State.MISSING -> "install Shizuku to sync them"
        }
        SetupAction.USAGE_ACCESS -> if (state.usageAccess) {
            "Play time is exact."
        } else {
            "Play time is approximate: sleep and other apps are counted too."
        }
        SetupAction.YOMIKATA -> "Memory samples and the inbox they go to."
        SetupAction.SCREEN_LOOK -> "How the built-in player draws each console (smoothed, sharp, scanlines or an LCD grid), its low-latency sound, and per-console button swaps."
        SetupAction.DICTIONARIES -> with(state.dictionaries) {
            if (isEmpty()) "None imported" else "${count { it.enabled }} of $size on (${filter { it.enabled }.joinToString { it.title }})"
        }
        SetupAction.WORDS -> "Known and ignored words, lookup counts, Hachidori."
        SetupAction.ANKI -> "The AnkiConnect server, deck and note type for mined cards."
        SetupAction.READING_LOG -> "Reading time and characters per game, logged in kechimochi."
        // Switch actions never sit on a button row; SettingsGroupsTest checks the groups.
        else -> ""
    }
}

private fun switchExplanation(key: SwitchKey, state: SetupState): String = when (key) {
    SwitchKey.HOME -> when {
        state.isOn(key) -> "Home comes back here. Switching off opens Android's Home settings."
        state.status.homeAliasEnabled -> "The Home entry is on, but another launcher is the default."
        else -> "Another launcher answers Home."
    }
    SwitchKey.BUILT_IN_PLAYER -> if (state.builtInPlayer) {
        "GB, GBA, Super Famicom, DS, PS1 and PSP games run inside Genkan. " +
            "Hold Select: +Start quits, +R1 saves a state, +L1 loads it, +left/right picks its slot, +R2 fast-forwards. " +
            "On a DS game, a stick click swaps its lower screen and the reader."
    } else {
        "Every game starts in its installed emulator."
    }
    SwitchKey.INSERT_ANIMATION -> if (state.insertAnimation) {
        "Starting a game slides its disc or cartridge into the machine first. Any button skips it."
    } else {
        "Games start without the animation."
    }
    SwitchKey.PREFETCH_MANUALS -> if (state.prefetchManuals) {
        "After each sync, installed games' manuals are fetched in the background, so they open offline."
    } else {
        "Manuals are fetched when you open them."
    }
    SwitchKey.FACE_A_ON_RIGHT -> if (state.faceAOnRight) {
        "Your device's A is its right button (Nintendo layout). The built-in player gives each game the buttons where its " +
            "console's pad had them: the right one is A on Nintendo consoles and circle on PlayStation."
    } else {
        "The built-in player assumes your device's A is its bottom button (Xbox layout). The launcher follows your device's " +
            "own button labels; if its A is on the right, switch this on so games get A (circle on PlayStation) on the right too."
    }
    SwitchKey.FULL_BOX -> if (state.fullBox) {
        "The focused game is drawn as its box, front and spine, where its scans have both."
    } else {
        "The focused game is drawn in its case."
    }
    SwitchKey.PSP_TEXT -> if (state.pspDebugger) {
        "PPSSPP's debugger is reachable from your network while a game runs."
    } else {
        "PSP games use OCR. Switching on opens PPSSPP's debugger to your network while a game runs."
    }
    SwitchKey.AZAHAR_TEXT -> if (state.azaharRpc) {
        "After OCR reads a line, Genkan follows it in memory. Azahar's RPC server is reachable from your network while a game runs."
    } else {
        "3DS games use OCR. Switching on opens Azahar's RPC server to your network while a game runs."
    }
}

/** The status at the right of a row, for the rows that can need attention. */
private fun status(row: SettingsRow, state: SetupState): Status? = when {
    row is SettingsRow.Switch && row.key == SwitchKey.HOME ->
        if (state.isOn(row.key)) null else Status("Not default", attention = true)
    row is SettingsRow.Button && row.action == SetupAction.SHIZUKU -> when (state.shizuku) {
        Privileged.State.READY -> Status("Ready")
        Privileged.State.NOT_ALLOWED -> Status("Not allowed", attention = true)
        Privileged.State.STOPPED -> Status("Stopped", attention = true)
        Privileged.State.MISSING -> Status("Not installed", attention = true)
    }
    row is SettingsRow.Button && row.action == SetupAction.USAGE_ACCESS ->
        if (state.usageAccess) Status("On") else Status("Off", attention = true)
    row is SettingsRow.Button && row.action == SetupAction.SYNC_LIBRARY && state.sync is SyncStatus.Failed ->
        Status("Failed", attention = true)
    else -> null
}
