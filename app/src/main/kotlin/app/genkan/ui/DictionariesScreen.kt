package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.dictionary.DictionaryEntity
import app.genkan.dictionary.ImportStatus
import java.text.NumberFormat

@Composable
fun DictionariesScreen(viewModel: DictionariesViewModel) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    val focusIndex by viewModel.focusIndex.collectAsStateWithLifecycle()
    val armed by viewModel.armedRemoval.collectAsStateWithLifecycle()
    val importStatus by viewModel.dictionaries.importStatus.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    LaunchedEffect(focusIndex) { listState.animateScrollToItem(focusIndex) }

    Column(
        Modifier.fillMaxSize().background(colors.background).padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Dictionaries", style = MaterialTheme.typography.displaySmall, color = colors.primary)
        Text(
            "A: on/off  ·  L1/R1: move up/down (the popup's order)  ·  X twice: remove  ·  B: back",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 1100.dp)) {
            item {
                ActionRow(
                    "Import dictionaries from Download" + when (val s = importStatus) {
                        is ImportStatus.Running -> "  ·  ${s.current} (${s.done + 1} of ${s.total})…"
                        is ImportStatus.Done -> "  ·  ${s.imported} new, ${s.skipped} already there" +
                            (if (s.failed.isEmpty()) "" else ", failed: ${s.failed.joinToString()}")
                        ImportStatus.Idle -> ""
                    },
                    focused = focusIndex == 0,
                    onClick = { viewModel.tap(0) },
                )
            }
            itemsIndexed(list, key = { _, d -> d.id }) { i, dictionary ->
                DictionaryRow(
                    dictionary,
                    focused = focusIndex == i + 1,
                    armed = armed == dictionary.id,
                    onTap = { viewModel.tap(i + 1) },
                    onUp = { viewModel.reorderAt(i + 1, -1) },
                    onDown = { viewModel.reorderAt(i + 1, 1) },
                )
            }
        }
    }
}

@Composable
private fun DictionaryRow(
    dictionary: DictionaryEntity,
    focused: Boolean,
    armed: Boolean,
    onTap: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) colors.primary.copy(alpha = 0.18f) else colors.surface)
            .clickable(onClick = onTap)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                dictionary.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (dictionary.enabled) colors.onSurface else colors.onSurfaceVariant,
            )
            Text(
                when {
                    armed -> "Press X again to remove it from this device"
                    else -> contents(dictionary) + if (dictionary.enabled) "" else "  ·  off"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (armed) colors.primary else colors.onSurfaceVariant,
            )
        }
        Text("▲", color = colors.onSurfaceVariant, modifier = Modifier.clickable(onClick = onUp).padding(8.dp))
        Text("▼", color = colors.onSurfaceVariant, modifier = Modifier.clickable(onClick = onDown).padding(8.dp))
    }
}

private fun contents(d: DictionaryEntity): String {
    val n = NumberFormat.getIntegerInstance()
    return listOfNotNull(
        d.terms.takeIf { it > 0 }?.let { "${n.format(it)} terms" },
        d.frequencies.takeIf { it > 0 }?.let { "${n.format(it)} frequencies" },
        d.pitches.takeIf { it > 0 }?.let { "${n.format(it)} pitch accents" },
        d.kanji.takeIf { it > 0 }?.let { "${n.format(it)} kanji" },
    ).joinToString("  ·  ")
}
