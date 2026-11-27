package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.anki.AnkiStatus

/**
 * AnkiConnect (Settings › Connections): where cards go, its key, and the word-audio source cards
 * fetch from. The deck, note type and word marks are on [AnkiSettingsScreen].
 */
@Composable
fun AnkiServerScreen(viewModel: AnkiSettingsViewModel) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val status by viewModel.miner.status.collectAsStateWithLifecycle()
    val pending by viewModel.miner.pending.collectAsStateWithLifecycle(0)
    val refused by viewModel.miner.refused.collectAsStateWithLifecycle(emptyList())
    val colors = MaterialTheme.colorScheme
    // The pad needs somewhere to start: focus the first field.
    val firstField = remember { FocusRequester() }
    // The result lands below the button, where the pad can't scroll: bring it into view.
    val scroll = rememberScrollState()
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(check) { if (opened) scroll.animateScrollTo(scroll.maxValue) else opened = true }
    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(scroll)
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("AnkiConnect", style = MaterialTheme.typography.displaySmall, color = colors.primary)
        Text(
            "Cards go to an AnkiConnect server (desktop Anki with the AnkiConnect add-on, listening on " +
                "0.0.0.0). Cards made offline wait here and are sent when it answers.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        HintRow(Hints.form())
        Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GenkanTextField(form.address, { v -> viewModel.edit { it.copy(address = v) } }, "AnkiConnect address (host or host:port)", modifier = Modifier.focusRequester(firstField))
            GenkanTextField(form.apiKey, { v -> viewModel.edit { it.copy(apiKey = v) } }, "API key (if AnkiConnect has one)", keyboardType = KeyboardType.Password)
            GenkanTextField(form.audioUrl, { v -> viewModel.edit { it.copy(audioUrl = v) } }, "Word audio: a Yomitan audio source URL with {term} and {reading} (optional)", keyboardType = KeyboardType.Uri)
            ActionRow("Save and test", focused = null, onClick = viewModel::saveAndTest)
        }
        check?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground) }
        Text(
            "Outbox: $pending waiting" + when (val s = status) {
                AnkiStatus.NotSet -> "  ·  no address set"
                is AnkiStatus.Unreachable -> "  ·  Anki unreachable (${s.reason})"
                is AnkiStatus.Sent -> "  ·  just sent ${s.count}"
                AnkiStatus.Idle -> ""
            },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onBackground,
        )
        refused.take(5).forEach {
            Text("Refused: ${it.expression}: ${it.error}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

/**
 * Anki cards (Settings › Anki): the deck and note type cards are made in, and word marks. The
 * server is on [AnkiServerScreen]; the deck and note type are picked from what it has.
 */
@Composable
fun AnkiSettingsScreen(viewModel: AnkiSettingsViewModel) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val choices by viewModel.choices.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val first = remember { FocusRequester() }
    val scroll = rememberScrollState()
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(check) { if (opened) scroll.animateScrollTo(scroll.maxValue) else opened = true }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(scroll)
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Anki cards", style = MaterialTheme.typography.displaySmall, color = colors.primary)
        HintRow(Hints.form())
        Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Picked from what Anki has: cards can't be sent without reaching it anyway.
            val known = choices
            if (known != null) {
                ChoiceField("Deck", form.deck, known.decks, modifier = Modifier.focusRequester(first)) { v -> viewModel.edit { it.copy(deck = v) } }
                ChoiceField("Note type (Lapis or Kiku)", form.noteType, known.noteTypes) { v -> viewModel.edit { it.copy(noteType = v) } }
                ActionRow("Save and test", focused = null, onClick = viewModel::saveAndTest)
            } else {
                Text(
                    "Deck and note type: chosen from your Anki once it answers (Settings › Connections › AnkiConnect).",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        check?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground) }
        Text("Word marks", style = MaterialTheme.typography.titleMedium, color = colors.primary)
        Text(
            "Underlines in the reader's backlog, as Hachidori draws them: solid for a word not in Anki, dashed " +
                "for one you're learning, none once its card is mature (a review interval of 21 days or more) or " +
                "you've marked it known or ignored. From the deck above, and any decks chosen here.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val marks = Modifier.let { if (choices == null) it.focusRequester(first) else it }
            ActionRow("Underline words by Anki state: " + if (form.marksWords) "on" else "off", focused = null, onClick = viewModel::toggleMarks, modifier = marks)
            val decks = choices?.decks?.filter { it != form.deck }
            if (decks == null) {
                Text("Other decks: listed once Anki answers.", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            } else {
                for (deck in decks) {
                    ActionRow((if (deck in form.markDecks) "✓ " else "    ") + "Also mark words from $deck", focused = null, onClick = { viewModel.toggleMarkDeck(deck) })
                }
            }
        }
    }
}
