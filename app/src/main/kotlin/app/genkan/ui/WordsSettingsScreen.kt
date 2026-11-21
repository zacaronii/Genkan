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
import app.genkan.words.HachidoriStatus
import app.genkan.words.WordStatus

/** Known and ignored words, and lookup counts: what the popup's buttons and chip keep. */
@Composable
fun WordsSettingsScreen(viewModel: WordsSettingsViewModel) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val paste by viewModel.paste.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val counting by viewModel.settings.counts.collectAsStateWithLifecycle(true)
    val counted by viewModel.counted.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()
    val linkStatus by viewModel.hachidori.status.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
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
            if (prefs.fromHachidori) {
                Text(
                    "The popup uses the linked Hachidori's settings: lookup counts " + (if (prefs.counts) "on" else "off") +
                        ", brief definitions " + (if (prefs.summary) "on (${prefs.summaryCount})" else "off") +
                        ", up to ${prefs.maxResults} results" + (prefs.frequencyDictionary?.let { ", sorted by $it" } ?: "") +
                        ". Change them in Hachidori; these below apply when it's unlinked.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            ActionRow(
                "Record and show lookup counts: " + if (counting) "on" else "off",
                focused = null,
                onClick = viewModel::toggleCounts,
                modifier = Modifier.focusRequester(first),
            )
            val summaryOn by viewModel.settings.summary.collectAsStateWithLifecycle(false)
            val summaryCount by viewModel.settings.summaryCount.collectAsStateWithLifecycle(3)
            ActionRow("Brief definitions beside each word (Hachidori's first meanings): " + if (summaryOn) "on" else "off", focused = null, onClick = viewModel::toggleSummary)
            if (summaryOn) ActionRow("How many brief definitions: $summaryCount", focused = null, onClick = viewModel::nextSummaryCount)
            ActionRow("Reset lookup counts ($counted word${if (counted == 1) "" else "s"})", focused = null, onClick = viewModel::resetCounts)
            GenkanTextField(paste, viewModel::editPaste, "Words to mark known (separated by spaces, commas or lines)", keyboardType = KeyboardType.Text)
            ActionRow("Mark these known", focused = null, onClick = viewModel::addPasted)
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground) }
        Text("Hachidori", style = MaterialTheme.typography.titleMedium, color = colors.primary)
        Text(
            "Share these marks and lookup counts with a Hachidori host through its relay (the Hachidori Relay " +
                "add-on in Anki, port 8771). Lookups and cards stay on this device; marks and counts made offline " +
                "wait here and go up when the host answers. The relay has no password: use it on your own network " +
                "or Tailscale.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GenkanTextField(link.address, { v -> viewModel.editLink { it.copy(address = v) } }, "Hachidori host (host, or a ws:// URL)")
            ActionRow(if (link.enabled) "Save and relink" else "Link to Hachidori", focused = null, onClick = { viewModel.saveLink(true) })
            if (link.enabled) ActionRow("Unlink", focused = null, onClick = { viewModel.saveLink(false) })
        }
        Text(
            "Hachidori: " + when (val s = linkStatus) {
                HachidoriStatus.Off -> "not linked"
                HachidoriStatus.Connecting -> "connecting…"
                is HachidoriStatus.Linked -> "linked (${s.version}); ${s.known} known, ${s.ignored} ignored" +
                    if (s.waiting > 0) "; ${s.waiting} waiting to go up" else "; all sent"
                is HachidoriStatus.Unreachable -> "unreachable (${s.reason}); trying again"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onBackground,
        )
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
