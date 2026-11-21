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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.words.WordStatus

/** Known and ignored words, and lookup counts: what the popup's buttons and chip keep. */
@Composable
fun WordsSettingsScreen(viewModel: WordsSettingsViewModel) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val paste by viewModel.paste.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val counting by viewModel.settings.counts.collectAsStateWithLifecycle(true)
    val counted by viewModel.counted.collectAsStateWithLifecycle()
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
        Text("Words", style = MaterialTheme.typography.displaySmall, color = colors.primary)
        Text(
            "The popup's ✓ Known and ⊘ Ignore buttons mark a word by its dictionary headword, for every reading " +
                "and form. Marked words are never underlined in the backlog, whatever Anki says. Press a lit button " +
                "again to clear it. B: back",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionRow(
                "Record and show lookup counts: " + if (counting) "on" else "off",
                focused = null,
                onClick = viewModel::toggleCounts,
                modifier = Modifier.focusRequester(first),
            )
            ActionRow("Reset lookup counts ($counted word${if (counted == 1) "" else "s"})", focused = null, onClick = viewModel::resetCounts)
            GenkanTextField(paste, viewModel::editPaste, "Words to mark known (separated by spaces, commas or lines)", keyboardType = KeyboardType.Text)
            ActionRow("Mark these known", focused = null, onClick = viewModel::addPasted)
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground) }
        for (status in WordStatus.entries) {
            val list = rows.filter { it.status == status }
            Text(
                "${viewModel.label(status)}: ${list.size}" + if (list.isNotEmpty()) "  ·  press one to remove it" else "",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground,
            )
            Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in list.take(MAX_SHOWN)) ActionRow(row.headword, focused = null, onClick = { viewModel.remove(row) })
                if (list.size > MAX_SHOWN) {
                    Text("…and ${list.size - MAX_SHOWN} more", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** Rows drawn per list: the newest marks, which are the likeliest to want undoing. */
private const val MAX_SHOWN = 200
