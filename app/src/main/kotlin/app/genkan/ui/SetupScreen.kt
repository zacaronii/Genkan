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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.anki.AnkiStatus
import app.genkan.core.PlayerChoice
import app.genkan.core.ScreenLook
import app.genkan.dictionary.DictionaryEntity
import app.genkan.home.DualScreenStatus
import app.genkan.library.SyncStatus
import app.genkan.privileged.Privileged
import app.genkan.romm.RomMState
import app.genkan.stats.ReadingLogStatus
import app.genkan.yomikata.CatalogStatus

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
    val armsxPine: Boolean = false,
    /** The Yomikata catalog, the last Anki send and the last reading-log push: as last seen. */
    val catalog: CatalogStatus = CatalogStatus.Idle,
    val anki: AnkiStatus = AnkiStatus.Idle,
    val readingLog: ReadingLogStatus = ReadingLogStatus.NotSet,
    /** The app's version, for About. */
    val version: String = "",
    /** The time the page was drawn, for the catalog's age (milliseconds since the epoch). */
    val nowMs: Long = 0,
    /** Whether the player learns each game's speed step. */
    val learning: Boolean = true,
    /** Each console's settings, for its row's line: looks chosen, sound, swaps, what plays it. */
    val looks: Map<String, ScreenLook> = emptyMap(),
    val lowLatencyOff: Set<String> = emptySet(),
    val faceSwapped: Set<String> = emptySet(),
    val coreChoice: Map<String, String> = emptyMap(),
    /** Whether AnkiConnect answered when Settings last asked; null until it has. */
    val ankiReach: AnkiReach? = null,
    /** What Storage holds, in bytes; null until measured. */
    val manualsBytes: Long? = null,
    val artBytes: Long? = null,
    /** The Clear row waiting for its second press, if any. */
    val armed: SetupAction? = null,
    /** How many games have a step of their own. */
    val tuningSteps: Int = 0,
    /** What the last Save a log did, if it ran. */
    val logSaved: String? = null,
) {
    /** Whether the switch for [key] shows on. */
    fun isOn(key: SwitchKey): Boolean = when (key) {
        SwitchKey.HOME -> homeOn(status)
        SwitchKey.BUILT_IN_PLAYER -> builtInPlayer
        SwitchKey.INSERT_ANIMATION -> insertAnimation
        SwitchKey.FULL_BOX -> fullBox
        SwitchKey.PREFETCH_MANUALS -> prefetchManuals
        SwitchKey.FACE_A_ON_RIGHT -> faceAOnRight
        SwitchKey.ARMSX2_TEXT -> armsxPine
        SwitchKey.PSP_TEXT -> pspDebugger
        SwitchKey.AZAHAR_TEXT -> azaharRpc
        SwitchKey.LEARNING -> learning
    }

    /**
     * What pressing [row] runs now: a switch runs its off action while it shows on. The pad (in
     * SetupViewModel) and a tap both ask here, so a switch with two actions can't differ by input.
     */
    fun actionFor(row: SettingsRow): SetupAction = when (row) {
        is SettingsRow.Button -> row.action
        is SettingsRow.Switch -> row.action(isOn(row.key))
        is SettingsRow.Console -> SetupAction.CONSOLE
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
            // More groups than fit: the list keeps the selected one in view, jumping to it (the pad
            // never moves Compose focus here).
            val list = rememberLazyListState()
            LaunchedEffect(focus.group) { keepInView(list, focus.group) }
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(groups) { index, group ->
                    GroupItem(
                        title = group.title,
                        selected = index == focus.group,
                        listFocused = focus.pane == SettingsPane.GROUPS,
                        onClick = { onSelectGroup(index) },
                    )
                }
            }
        }
        val group = groups.getOrNull(focus.group) ?: return@Row
        // Keyed on the group so each one opens scrolled to its top.
        // A row's help, while it's open (Y), in the place of the rows.
        val helpRow = group.rows.getOrNull(focus.row)?.takeIf { focus.help }
        val help = helpRow?.let(SettingsHelp::of)
        if (helpRow != null && help != null) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(group.title, style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
                HelpSheet(helpRow.title, help)
            }
            return@Row
        }
        key(focus.group) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(group.title, style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
                group.info.forEach { info ->
                    if (info == SettingsInfo.STATUS) {
                        StatusCard(statusLines(state))
                    } else if (info == SettingsInfo.SHORTCUTS) {
                        ShortcutsTable(SettingsHelp.SHORTCUTS)
                    } else {
                        Text(infoLine(info, state), style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                    }
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
        if (focus.help) Hints.settingsHelp() else Hints.settings(
            onRows = focus.pane == SettingsPane.ROWS,
            help = focus.pane == SettingsPane.ROWS && focusedRow?.let(SettingsHelp::of) != null,
            groupOpens = groups.getOrNull(focus.group)?.rows?.isNotEmpty() == true,
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

/** Scrolls [list] just enough to show item [index] whole: to its top above the view, to its bottom below. */
private suspend fun keepInView(list: LazyListState, index: Int) {
    // Laid out first, so what's visible is known.
    snapshotFlow { list.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
    val info = list.layoutInfo
    val visible = info.visibleItemsInfo
    val first = visible.first().index
    val whole = visible.filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }.map { it.index }
    when {
        index in whole -> Unit
        index <= first -> list.scrollToItem(index)
        else -> {
            val item = visible.firstOrNull { it.index == index }
            if (item != null) {
                list.scrollBy((item.offset + item.size - info.viewportEndOffset).toFloat())
            } else {
                // Off the end: put it last, the items before it filling the view.
                list.scrollToItem(index)
                val now = list.layoutInfo
                val at = now.visibleItemsInfo.first { it.index == index }
                list.scrollBy((at.offset + at.size - now.viewportEndOffset).toFloat())
            }
        }
    }
}

/** The built-in player's shortcuts: the buttons, and what each does. */
@Composable
private fun ShortcutsTable(rows: List<Pair<String, String>>) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("In the built-in player", style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
        rows.forEach { (keys, does) ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(keys, Modifier.width(170.dp), style = MaterialTheme.typography.titleSmall, color = colors.primary)
                Text(does, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            }
        }
    }
}

/** A row's longer help (Y), over the rows: its title and text. */
@Composable
private fun HelpSheet(title: String, text: String) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(12.dp))
            .border(2.dp, colors.primary, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = colors.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
    }
}

/** One line of the status card: what, its state, and whether it needs doing something. */
data class StatusLine(val label: String, val value: String, val attention: Boolean = false)

/** Every connection and state at a glance, as last seen; nothing on it is pressed. */
@Composable
private fun StatusCard(lines: List<StatusLine>) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lines.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(line.label, Modifier.width(150.dp), style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
                Text(
                    line.value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (line.attention) colors.error else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The status card's lines, from what the page already knows. */
fun statusLines(state: SetupState): List<StatusLine> = listOf(
    when (val r = state.romm) {
        is RomMState.SignedIn -> StatusLine("RomM", "${r.account.username} on ${r.account.baseUrl.removeSuffix("/").substringAfter("://")}")
        else -> StatusLine("RomM", "Not signed in", attention = true)
    },
    when (val s = state.sync) {
        is SyncStatus.Done -> StatusLine("Library", "${s.games} games, synced")
        is SyncStatus.Running -> StatusLine("Library", "Syncing ${s.platform}…")
        is SyncStatus.Failed -> StatusLine("Library", "Sync failed: ${s.message}", attention = true)
        SyncStatus.Idle -> StatusLine("Library", "Not synced yet")
    },
    StatusLine("Built-in player", if (state.builtInPlayer) "On" else "Off"),
    when (val c = state.catalog) {
        is CatalogStatus.Ready -> StatusLine("Yomikata", "${c.entries} games, updated ${age(state.nowMs - c.updatedAt)}")
        is CatalogStatus.Failed -> StatusLine("Yomikata", "Unreachable: ${c.reason}", attention = true)
        CatalogStatus.NotSet -> StatusLine("Yomikata", "No server set")
        CatalogStatus.Idle -> StatusLine("Yomikata", "Not fetched yet")
    },
    when (val reach = state.ankiReach) {
        is AnkiReach.Reachable -> StatusLine("AnkiConnect", "Reachable (AnkiConnect ${reach.version})")
        is AnkiReach.Unreachable -> StatusLine("AnkiConnect", "Unreachable: ${reach.reason}", attention = true)
        AnkiReach.NotSet -> StatusLine("AnkiConnect", "No server set")
        null -> ankiLine(state.anki)
    },
    when (val l = state.readingLog) {
        ReadingLogStatus.UpToDate -> StatusLine("kechimochi", "Up to date")
        is ReadingLogStatus.Sent -> StatusLine("kechimochi", "${l.sessions} sessions sent")
        is ReadingLogStatus.Failed -> StatusLine("kechimochi", "Unreachable: ${l.reason}", attention = true)
        ReadingLogStatus.NotSet -> StatusLine("kechimochi", "No server set")
    },
    StatusLine("Second screen", state.status.secondaryDisplayId?.let { "Display $it" } ?: "Not found", attention = state.status.secondaryDisplayId == null),
    StatusLine("Companion", with(state.status) {
        when {
            !secondaryUsable -> "Refused by the system"
            lowerDisplayId != null -> "Running"
            !companionEnabled -> "Released"
            else -> "Not running"
        }
    }, attention = !state.status.secondaryUsable),
)

/** AnkiConnect's line before Settings has asked it: what the last send found. */
private fun ankiLine(status: AnkiStatus): StatusLine = when (status) {
    is AnkiStatus.Sent -> StatusLine("AnkiConnect", "Reachable, ${status.count} cards sent")
    is AnkiStatus.Unreachable -> StatusLine("AnkiConnect", "Unreachable: ${status.reason}", attention = true)
    AnkiStatus.NotSet -> StatusLine("AnkiConnect", "No server set")
    AnkiStatus.Idle -> StatusLine("AnkiConnect", "Nothing sent yet")
}

/** Whether AnkiConnect answered when Settings asked (SetupViewModel.checkServers). */
sealed interface AnkiReach {
    data object NotSet : AnkiReach
    data class Reachable(val version: Int) : AnkiReach
    data class Unreachable(val reason: String) : AnkiReach
}

/** A size for Storage: whole megabytes past one, else kilobytes. */
fun bytes(size: Long): String = when {
    size >= 1_000_000 -> "${(size + 500_000) / 1_000_000} MB"
    size >= 1_000 -> "${(size + 500) / 1_000} KB"
    else -> "$size B"
}

/** How long ago, for the status card: minutes, hours, then days. */
fun age(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
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
    // Drawn as a table (ShortcutsTable), never as a line.
    SettingsInfo.SHORTCUTS -> ""
    SettingsInfo.VERSION -> "Genkan ${state.version}"
    SettingsInfo.LICENCE -> "Free software under the GPL-3.0. The code it borrows and the cores it builds are under Licences."
    // Drawn as the card (StatusCard), never as a line.
    SettingsInfo.STATUS -> ""
}

/** The line under a row's title: what it does, or what its state means. */
private fun explanation(row: SettingsRow, state: SetupState): String = when (row) {
    is SettingsRow.Switch -> switchExplanation(row.key, state)
    is SettingsRow.Console -> consoleSummary(row.platform, state)
    is SettingsRow.Button -> when (row.action) {
        SetupAction.SYNC_LIBRARY -> when (val s = state.sync) {
            is SyncStatus.Done -> "Fetch RomM's games now (${s.games} games)"
            is SyncStatus.Running -> "Syncing ${s.platform}…"
            is SyncStatus.Failed -> "The last sync failed: ${s.message}"
            SyncStatus.Idle -> "Fetch RomM's games now"
        }
        SetupAction.SIGN_OUT -> "Forget the RomM account on this device"
        SetupAction.SHIZUKU -> when (state.shizuku) {
            Privileged.State.READY -> "Syncs DuckStation and Dolphin saves"
            Privileged.State.NOT_ALLOWED -> "Running: press to allow Genkan"
            Privileged.State.STOPPED -> "Start it to sync DuckStation and Dolphin saves"
            Privileged.State.MISSING -> "Install it to sync DuckStation and Dolphin saves"
        }
        SetupAction.USAGE_ACCESS -> if (state.usageAccess) {
            "Only time in the game counts"
        } else {
            "Sleep and other apps count as play time too"
        }
        SetupAction.YOMIKATA -> "RAM snapshots of unread lines, for your inbox"
        SetupAction.YOMIKATA_SERVERS -> "The script catalog and the samples inbox"
        SetupAction.ANKI_SERVER -> "Where cards go, and the word-audio source"
        SetupAction.NOTICE -> "The code Genkan borrows and builds, with its licences"
        SetupAction.CLEAR_MANUALS -> if (state.armed == row.action) "Press again to clear them" else "Kept to open offline; press to clear"
        SetupAction.CLEAR_ART -> if (state.armed == row.action) "Press again to clear them" else "Fetched again as the shelf shows them"
        SetupAction.TUNING_STEPS -> if (state.tuningSteps == 0) "No game has a step of its own" else "${state.tuningSteps} games with a step of their own"
        SetupAction.SAVE_LOG -> state.logSaved ?: "To Download on this device; never sent"
        SetupAction.HEADPHONES -> "Reconnect paired headphones"

        SetupAction.DICTIONARIES -> with(state.dictionaries) {
            if (isEmpty()) "None imported" else "${count { it.enabled }} of $size on (${filter { it.enabled }.joinToString { it.title }})"
        }
        SetupAction.WORDS -> "Known and ignored words, counts, Hachidori"
        SetupAction.ANKI -> "Deck, note type and word marks"
        SetupAction.READING_LOG -> "Reading time and characters per game, logged there"
        // Switch actions never sit on a button row; SettingsGroupsTest checks the groups.
        else -> ""
    }
}

private fun switchExplanation(key: SwitchKey, state: SetupState): String = when (key) {
    SwitchKey.HOME -> when {
        state.isOn(key) -> "Home comes back here"
        state.status.homeAliasEnabled -> "On, but another launcher is the default"
        else -> "Another launcher answers Home"
    }
    SwitchKey.BUILT_IN_PLAYER -> if (state.builtInPlayer) {
        "Play supported consoles inside Genkan"
    } else {
        "Every game starts in its emulator app"
    }
    SwitchKey.INSERT_ANIMATION -> if (state.insertAnimation) {
        "A game's disc or cartridge goes in first"
    } else {
        "Games start without the animation"
    }
    SwitchKey.PREFETCH_MANUALS -> if (state.prefetchManuals) {
        "Installed games' manuals download after a sync"
    } else {
        "A manual downloads when you open it"
    }
    SwitchKey.FACE_A_ON_RIGHT -> if (state.faceAOnRight) {
        "Games get A, or circle, on the right button"
    } else {
        "For a pad whose A is the right button"
    }
    SwitchKey.FULL_BOX -> if (state.fullBox) {
        "The focused game shows as its whole box"
    } else {
        "The focused game shows in its case"
    }
    SwitchKey.PSP_TEXT -> if (state.pspDebugger) {
        "Read from PPSSPP's memory (debugger on your network)"
    } else {
        "Uses OCR; on opens PPSSPP's debugger to your network"
    }
    SwitchKey.ARMSX2_TEXT -> if (state.armsxPine) {
        "Read from ARMSX2's memory (PINE, in its Advanced settings)"
    } else {
        "Uses OCR; on needs PINE, in ARMSX2's Advanced settings"
    }
    SwitchKey.LEARNING -> if (state.learning) {
        "A slow game tries a lighter setting, a quick one a sharper"
    } else {
        "Each game keeps the setting it starts with"
    }
    SwitchKey.AZAHAR_TEXT -> if (state.azaharRpc) {
        "Read from Azahar's memory (RPC on your network)"
    } else {
        "Uses OCR; on opens Azahar's RPC to your network"
    }
}

/** A console row's line: its look, then what differs from the usual (sound, buttons, player, text). */
fun consoleSummary(platform: String, state: SetupState): String {
    val look = state.looks[platform] ?: ScreenLook.defaultFor(platform)
    val parts = listOfNotNull(
        look.label,
        "plain sound".takeIf { platform in state.lowLatencyOff },
        "A/B swapped".takeIf { platform in state.faceSwapped },
        // The player only when it isn't the console's usual one.
        PlayerChoice.current(platform, state.coreChoice)?.takeIf { it != PlayerChoice.current(platform, emptyMap()) }?.label,
        consoleText(platform, state)?.takeIf { it.on }?.let { "text from memory" },
    )
    return parts.joinToString(", ")
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
    row is SettingsRow.Button && row.action == SetupAction.CLEAR_MANUALS -> state.manualsBytes?.let { Status(bytes(it)) }
    row is SettingsRow.Button && row.action == SetupAction.CLEAR_ART -> state.artBytes?.let { Status(bytes(it)) }
    row is SettingsRow.Button && row.action == SetupAction.SYNC_LIBRARY && state.sync is SyncStatus.Failed ->
        Status("Failed", attention = true)
    else -> null
}
