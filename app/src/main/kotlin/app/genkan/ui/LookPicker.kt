package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.core.ScreenLook

/** One entry of the picker: a look, or "the console's" ([look] null) for a game's own. */
data class LookChoice(val look: ScreenLook?, val label: String, val description: String)

object LookPickerEntries {
    /**
     * The picker's entries under their headings: with [console] (a game's override), "Console's"
     * first, then each family's looks in the order [ScreenLook] lists them. With [suits] (the
     * console's looks, [ScreenLook.suiting]) only those, and [keep] (the looks in use) wherever
     * they are, unless [showAll]; a family left with none has no heading.
     */
    fun of(
        console: ScreenLook? = null,
        suits: Set<ScreenLook>? = null,
        keep: Set<ScreenLook> = emptySet(),
        showAll: Boolean = false,
    ): List<Pair<String?, List<LookChoice>>> = buildList {
        console?.let { add(null to listOf(LookChoice(null, "Console's (${it.label})", "Whatever its console uses: ${it.description}"))) }
        val shown = ScreenLook.entries.filter { showAll || suits == null || it in suits || it in keep }
        shown.groupBy { it.family }.forEach { (family, looks) ->
            add(family.title to looks.map { LookChoice(it, it.label, it.description) })
        }
    }

    /** Whether a list for [suits] leaves looks out, so it offers "show all". */
    fun filters(suits: Set<ScreenLook>?): Boolean = suits != null && suits.size < ScreenLook.entries.size
}

/**
 * A list of the looks to pick from, grouped by family, [selected] marked (null with [console]: the
 * console's). Each row says what the look does. [focusSelected] gives the selected row Compose
 * focus as it opens, for the pad (the top screen's settings); on the bottom screen it's touch.
 * [onPick] gets the look (null: the console's); [onClose] closes it, as does Back.
 */
@Composable
fun LookPicker(
    title: String,
    selected: ScreenLook?,
    onPick: (ScreenLook?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    console: ScreenLook? = null,
    /** The looks that suit the console ([ScreenLook.suiting]), listed unless "show all"; null lists every look. */
    suits: Set<ScreenLook>? = null,
    focusSelected: Boolean = false,
    closeLabel: String = "Close",
    /** Whether the list opens showing every look; off in the app, so the console's come first. */
    showAllAtFirst: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    var showAll by remember { mutableStateOf(showAllAtFirst) }
    val filters = LookPickerEntries.filters(suits)
    // Every look in use since the list opened stays listed (the reader's list stays open across picks), to compare.
    val kept = remember { mutableSetOf<ScreenLook>() }
    selected?.let { kept += it }
    val groups = remember(console, suits, selected, showAll) { LookPickerEntries.of(console, suits, kept.toSet(), showAll) }
    val focus = remember { FocusRequester() }
    val toggleFocus = remember { FocusRequester() }
    // The selected row's place in the list (headings take a row each), to start there.
    val selectedIndex = remember(groups, selected) {
        var index = 0
        var found = 0
        for ((heading, choices) in groups) {
            if (heading != null) index++
            choices.forEach { if (it.look == selected) found = index; index++ }
        }
        found
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    // A frame first: the selected row is a lazy item, laid out (and its requester attached) after this runs.
    if (focusSelected) LaunchedEffect(Unit) { withFrameNanos {}; runCatching { focus.requestFocus() } }
    Column(
        modifier
            .fillMaxSize()
            // A touch anywhere on the list is the list's: hit here, it never reaches the buttons or chips underneath.
            .pointerInput(Unit) {}
            .background(colors.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // At most two lines, so a long game name can't make the header taller than the list allows.
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // In the header, so it's in view wherever the list is scrolled.
            if (filters) {
                Box(Modifier.focusRequester(toggleFocus)) {
                    Chip(if (showAll) "Show fewer" else "Show all ${ScreenLook.entries.size}") { showAll = !showAll }
                }
            }
            Chip(closeLabel, onClose)
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            groups.forEach { (heading, choices) ->
                heading?.let { item(key = "h-$it") { Text(it, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) } }
                choices.forEach { choice ->
                    item(key = choice.look?.name ?: "console") {
                        val isSelected = choice.look == selected
                        LookRow(
                            choice,
                            isSelected,
                            onClick = { onPick(choice.look) },
                            modifier = Modifier
                                // On the pad, Right from any look goes to the "show all" chip.
                                .then(if (filters) Modifier.focusProperties { right = toggleFocus } else Modifier)
                                .then(if (isSelected && focusSelected) Modifier.focusRequester(focus) else Modifier),
                        )
                    }
                }
            }
        }
    }
}

/** A look's row: its name, what it does, and a mark when it's the one in use. */
@Composable
private fun LookRow(choice: LookChoice, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .background(if (selected) colors.primary.copy(alpha = 0.18f) else colors.surface, shape)
            .border(2.dp, if (focused) colors.primary else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(choice.label, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(choice.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (selected) Text("✓", style = MaterialTheme.typography.titleMedium, color = colors.primary)
    }
}

/**
 * The looks over the reader while a game plays in the built-in player: a pick draws the game with
 * it at once (for this play), and the list stays open to compare until Close or Back.
 */
@Composable
fun ReaderLookPicker(viewModel: ReaderViewModel) {
    val picking by viewModel.pickingLook.collectAsStateWithLifecycle()
    val look by viewModel.look.collectAsStateWithLifecycle()
    val now = look?.first ?: return
    if (!picking) return
    BottomBackHandler(onBack = viewModel::closeLookPicker)
    val game by viewModel.session.game.collectAsStateWithLifecycle()
    val platform = game.platform
    LookPicker(
        title = "Look for this play",
        selected = now,
        suits = ScreenLook.suiting(platform),
        onPick = { it?.let(viewModel::tryLook) },
        onClose = viewModel::closeLookPicker,
    )
}
