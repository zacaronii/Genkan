package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.dictionary.GlossaryText
import app.genkan.dictionary.TermHit
import app.genkan.reader.HookStatus
import app.genkan.reader.ReaderLineEntity
import app.genkan.reader.ThreadSample

/** The bottom screen: the current game's backlog, tap a word to look it up. */
@Composable
fun ReaderScreen(viewModel: ReaderViewModel) {
    val lines by viewModel.session.lines.collectAsStateWithLifecycle()
    val status by viewModel.session.status.collectAsStateWithLifecycle()
    val game by viewModel.session.game.collectAsStateWithLifecycle()
    val threads by viewModel.session.threads.collectAsStateWithLifecycle()
    val chosen by viewModel.session.chosenThread.collectAsStateWithLifecycle()
    val picking by viewModel.picking.collectAsStateWithLifecycle()
    val lookup by viewModel.lookup.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(game.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1)
                Text(
                    when (status) {
                        is HookStatus.Connected -> "Texthooker connected"
                        is HookStatus.Waiting -> "Waiting for a texthooker on ${status.address.removePrefix("ws://")}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (threads.isNotEmpty() || chosen != null) {
                Chip("Thread: ${chosen?.thread ?: "choose"}") { viewModel.openPicker() }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val panelHeight = maxHeight * PANEL_FRACTION
            val needsChoice = threads.isNotEmpty() && chosen == null
            if (picking || needsChoice) {
                ThreadPicker(threads, chosen?.thread, onChoose = viewModel::choose, onClose = viewModel::closePicker.takeIf { !needsChoice })
            } else {
                Backlog(lines, lookup, panelHeight, onTap = viewModel::lookUp, onBackground = viewModel::closeLookup)
            }
            lookup?.let { current ->
                LookupPanel(current, onClose = viewModel::closeLookup, modifier = Modifier.align(Alignment.BottomCenter).height(panelHeight))
            }
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = colors.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Backlog(
    lines: List<ReaderLineEntity>,
    lookup: Lookup?,
    panelHeight: Dp,
    onTap: (ReaderLineEntity, Int) -> Unit,
    onBackground: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val lookupLine = lookup?.lineId
    // Follow new lines as they arrive, unless a word is being looked up.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && lookupLine == null) listState.animateScrollToItem(lines.lastIndex)
    }
    // Keep the looked-up line in view above the panel.
    LaunchedEffect(lookupLine) {
        val index = lines.indexOfFirst { it.id == lookupLine }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Lines from the game show up here. Tap a word to look it up.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onBackground() } },
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp + if (lookupLine != null) panelHeight else 0.dp),
    ) {
        items(lines, key = { it.id }) { line ->
            val highlight = lookup?.takeIf { it.lineId == line.id }
            TappableLine(line.text, highlight?.start, highlight?.end) { onTap(line, it) }
        }
    }
}

/** A line whose characters can be tapped; the looked-up word is highlighted. */
@Composable
private fun TappableLine(text: String, start: Int?, end: Int?, onTapAt: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val annotated = buildAnnotatedString {
        if (start == null || end == null || end <= start) {
            append(text)
        } else {
            append(text.substring(0, start))
            withStyle(SpanStyle(background = colors.primary.copy(alpha = 0.35f))) { append(text.substring(start, end.coerceAtMost(text.length))) }
            append(text.substring(end.coerceAtMost(text.length)))
        }
    }
    BasicText(
        annotated,
        style = MaterialTheme.typography.headlineSmall.copy(color = colors.onBackground, fontSize = 26.sp, lineHeight = 38.sp),
        onTextLayout = { layout.value = it },
        modifier = Modifier.fillMaxWidth().pointerInput(text) {
            detectTapGestures { position ->
                layout.value?.let { onTapAt(it.getOffsetForPosition(position).coerceAtMost(text.length - 1)) }
            }
        },
    )
}

@Composable
private fun ThreadPicker(threads: List<ThreadSample>, chosen: String?, onChoose: (ThreadSample) -> Unit, onClose: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Which thread is the dialogue?",
                style = MaterialTheme.typography.titleLarge,
                color = colors.primary,
                modifier = Modifier.weight(1f),
            )
            onClose?.let { Chip("Back", it) }
        }
        Text(
            if (threads.isEmpty()) "No threads yet: play until some text shows, then choose." else
                "Tap the one showing the game's text. Genkan remembers it for this game.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(threads, key = { it.thread }) { sample ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sample.thread == chosen) colors.primaryContainer else colors.surface)
                        .clickable { onChoose(sample) }
                        .padding(16.dp),
                ) {
                    Text("${sample.thread}  ·  ${sample.lines} lines", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    Text(sample.latest, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun LookupPanel(lookup: Lookup, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(colors.surface)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    lookup.result == null -> "Looking up…"
                    lookup.result.hits.isEmpty() -> "Nothing found"
                    else -> lookup.result.hits.first().matched
                },
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Chip("Close", onClose)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            lookup.result?.hits?.forEach { HitView(it) }
        }
    }
}

@Composable
private fun HitView(hit: TermHit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(hit.expression, style = MaterialTheme.typography.headlineMedium, color = colors.onSurface)
            if (hit.reading.isNotEmpty() && hit.reading != hit.expression) {
                Text(hit.reading, style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
            }
        }
        val notes = buildList {
            if (hit.trace.isNotEmpty()) add(hit.trace.joinToString(" ← ") { it.name })
            hit.frequencies.firstOrNull()?.let { f ->
                f.values.firstOrNull()?.let { add("${f.dictionary} ${it.display.ifEmpty { it.value.toString() }}") }
            }
            hit.pitches.firstOrNull()?.pitches?.takeIf { it.isNotEmpty() }?.let { p -> add("pitch " + p.joinToString(" ") { "[${it.position}]" }) }
        }
        if (notes.isNotEmpty()) {
            Text(notes.joinToString("  ·  "), style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
        hit.glossaries.groupBy { it.dictionary }.forEach { (dictionary, entries) ->
            Text(dictionary, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            val definitions = entries.flatMap { GlossaryText.definitions(it.glossary) }
            Text(
                definitions.joinToString("; "),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
        }
    }
}

/** Share of the reader the lookup panel takes. */
private const val PANEL_FRACTION = 0.55f
