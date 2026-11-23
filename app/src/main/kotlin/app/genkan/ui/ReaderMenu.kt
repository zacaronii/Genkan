package app.genkan.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.manual.ManualFetch
import app.genkan.ppsspp.HookFinderState

/** What a row of the reader's menu does. */
enum class ReaderAction { GAME, MANUAL, OCR_AREA, OCR_ENGINE, AUDIO, FIND_HOOK, STOP_HOOK, HOOK_DONE, OVERLAY, LOOK, KEEP_LOOK, THREAD, HEADPHONES }

/**
 * A row of the reader's menu: [state] shows on its right (on or off, the look or engine in use);
 * [opens]: it opens something else (marked ›), and the menu closes; [attention]: it wants a look
 * (the menu's button carries a dot).
 */
data class ReaderMenuItem(
    val action: ReaderAction,
    val label: String,
    val state: String? = null,
    val opens: Boolean = false,
    val attention: Boolean = false,
    val enabled: Boolean = true,
) {
    /** Pressing it closes the menu: it opens something, or asks Android for a permission. */
    val closes: Boolean get() = opens || action == ReaderAction.AUDIO
}

/** What the reader's bar knows, for its menu: everything but OCR and Snapshot, which stay on the bar. */
data class ReaderBarState(
    val canShowGame: Boolean = false,
    /** The playing game has a manual, and it isn't open. */
    val manual: Boolean = false,
    val manualFetch: ManualFetch? = null,
    val ocrOn: Boolean = false,
    val capturing: Boolean = false,
    val engine: String = "",
    val recording: Boolean = false,
    val finder: HookFinderState = HookFinderState.Idle,
    /** A PSP game with no hook profile: the hook finder can look for one. */
    val canFindHook: Boolean = false,
    val overlayAvailable: Boolean = false,
    val overlayOn: Boolean = false,
    /** The look on screen, and whether it's the game's own. */
    val look: Pair<String, Boolean>? = null,
    val hasThreads: Boolean = false,
    val thread: String? = null,
    /** The headphones list can open here. */
    val headphones: Boolean = false,
)

object ReaderMenu {
    /** The menu's rows, in the order the bar's chips had, each only when it applies. */
    fun items(s: ReaderBarState): List<ReaderMenuItem> = buildList {
        if (s.canShowGame) add(ReaderMenuItem(ReaderAction.GAME, "Game", opens = true))
        if (s.manual) {
            val fetch = s.manualFetch
            add(
                ReaderMenuItem(
                    ReaderAction.MANUAL, "Manual",
                    state = when (fetch) {
                        is ManualFetch.Busy -> fetch.progress?.let { "Fetching ${(it * 100).toInt()}%" } ?: "Fetching…"
                        is ManualFetch.Failed -> fetch.message
                        null -> null
                    },
                    opens = true,
                    attention = fetch is ManualFetch.Failed,
                    enabled = fetch !is ManualFetch.Busy,
                ),
            )
        }
        if (s.ocrOn && s.capturing) add(ReaderMenuItem(ReaderAction.OCR_AREA, "OCR area", opens = true))
        if (s.ocrOn) add(ReaderMenuItem(ReaderAction.OCR_ENGINE, "OCR engine", state = s.engine))
        // Sentence audio rides on the capture; it needs the recording permission once.
        if (s.capturing && !s.recording) add(ReaderMenuItem(ReaderAction.AUDIO, "Sentence audio", state = "Allow…"))
        when (s.finder) {
            is HookFinderState.Working -> add(ReaderMenuItem(ReaderAction.STOP_HOOK, "Finding the hook", state = "Stop"))
            is HookFinderState.Found -> add(ReaderMenuItem(ReaderAction.HOOK_DONE, "Hook found", state = "OK", attention = true))
            is HookFinderState.Failed -> add(ReaderMenuItem(ReaderAction.HOOK_DONE, "No hook found", state = "OK", attention = true))
            HookFinderState.Idle -> if (s.canFindHook) add(ReaderMenuItem(ReaderAction.FIND_HOOK, "Find hook"))
        }
        if (s.overlayAvailable) add(ReaderMenuItem(ReaderAction.OVERLAY, "Overlay", state = if (s.overlayOn) "On" else "Off"))
        s.look?.let { (label, kept) ->
            add(ReaderMenuItem(ReaderAction.LOOK, "Look", state = label, opens = true))
            if (!kept) add(ReaderMenuItem(ReaderAction.KEEP_LOOK, "Keep $label for this game", attention = true))
        }
        if (s.hasThreads || s.thread != null) add(ReaderMenuItem(ReaderAction.THREAD, "Thread", state = s.thread ?: "choose", opens = true))
        if (s.headphones) add(ReaderMenuItem(ReaderAction.HEADPHONES, "Bluetooth headphones", opens = true))
    }
}

/** The bar's button for the menu, with a dot when a row wants a look. */
@Composable
internal fun ReaderMenuButton(open: Boolean, attention: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .clip(shape)
            .background(if (open) colors.primary.copy(alpha = 0.25f) else colors.secondaryContainer)
            .border(2.dp, if (focused) colors.primary else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("⋯ Reader ▾", style = MaterialTheme.typography.labelLarge, color = colors.onSecondaryContainer)
        if (attention) Box(Modifier.size(8.dp).background(colors.error, CircleShape))
    }
}

/**
 * The menu's rows, under the bar's right end. It's drawn in the reader, not in a window of its
 * own: on the bottom screen that window never takes the pad from the game, so the menu works by
 * touch there and Back (the pad's B on the top screen) closes it; where the reader has the pad,
 * the first row takes focus and the pad moves through the rows.
 */
@Composable
internal fun ReaderMenuPanel(items: List<ReaderMenuItem>, onPick: (ReaderMenuItem) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val first = remember { FocusRequester() }
    Column(
        modifier
            .width(MENU_WIDTH)
            .background(colors.surface, RoundedCornerShape(12.dp))
            .border(1.dp, colors.onSurface.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(6.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { i, item ->
            ReaderMenuRow(item, onClick = { onPick(item) }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun ReaderMenuRow(item: ReaderMenuItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val text = if (item.enabled) colors.onSurface else colors.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .border(2.dp, if (focused) colors.primary else Color.Transparent, shape)
            .clickable(enabled = item.enabled, interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(item.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = text, maxLines = 1)
        if (item.attention) Box(Modifier.size(8.dp).background(colors.error, CircleShape))
        item.state?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    item.attention -> colors.error
                    it == "On" -> colors.primary
                    else -> colors.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
        if (item.opens) Text("›", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
    }
}

private val MENU_WIDTH = 340.dp
