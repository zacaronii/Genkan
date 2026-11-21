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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.genkan.words.WordMarkState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.memory.MemoryTextStatus
import app.genkan.ppsspp.HookFinderState
import app.genkan.ppsspp.PpssppStatus
import app.genkan.reader.HookStatus
import app.genkan.reader.ReaderLineEntity
import app.genkan.reader.ThreadSample

/** The bottom screen: the current game's backlog, tap a word to look it up. */
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onAskCaptureConsent: () -> Unit,
    onAskAudioPermission: () -> Unit,
    /** Set while browsing the library: back to the focused game's details. */
    onShowGame: (() -> Unit)? = null,
) {
    val lines by viewModel.session.lines.collectAsStateWithLifecycle()
    val status by viewModel.session.status.collectAsStateWithLifecycle()
    val game by viewModel.session.game.collectAsStateWithLifecycle()
    val threads by viewModel.session.threads.collectAsStateWithLifecycle()
    val chosen by viewModel.session.chosenThread.collectAsStateWithLifecycle()
    val picking by viewModel.picking.collectAsStateWithLifecycle()
    val lookup by viewModel.lookup.collectAsStateWithLifecycle()
    val popup by viewModel.popup.collectAsStateWithLifecycle()
    val ocrOn by viewModel.ocr.enabled.collectAsStateWithLifecycle()
    val capturing by viewModel.capture.running.collectAsStateWithLifecycle()
    val ocrMs by viewModel.ocr.lastReadMs.collectAsStateWithLifecycle()
    val engine by viewModel.ocr.engine.collectAsStateWithLifecycle()
    val region by viewModel.ocr.region.collectAsStateWithLifecycle()
    val editing by viewModel.editingArea.collectAsStateWithLifecycle()
    val recording by viewModel.audio.running.collectAsStateWithLifecycle()
    val reading by viewModel.stats.session.collectAsStateWithLifecycle()
    val ppsspp by viewModel.ppsspp.status.collectAsStateWithLifecycle()
    val finder by viewModel.hookFinder.state.collectAsStateWithLifecycle()
    val marks by viewModel.lineMarks.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val layoutActive by viewModel.builtIn.layoutActive.collectAsStateWithLifecycle()
    val playing by viewModel.builtIn.playing.collectAsStateWithLifecycle()
    val sampling by viewModel.builtIn.sampling.collectAsStateWithLifecycle(false)
    val azahar by viewModel.azahar.status.collectAsStateWithLifecycle()
    val builtIn by viewModel.builtIn.status.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                // One line that ends in "…": wrapped, it would show only its first words with no sign of the rest.
                Text(game.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    // This session's reading, then where the text comes from.
                    (reading?.let { "${"%,d".format(it.characters)} chars, ${it.activeMs / 60_000} min  ·  " } ?: "") + when {
                        notice != null -> notice!!
                        finder is HookFinderState.Working -> (finder as HookFinderState.Working).message
                        finder is HookFinderState.Found -> (finder as HookFinderState.Found).message
                        finder is HookFinderState.Failed -> (finder as HookFinderState.Failed).message
                        ocrOn && !capturing -> "OCR: waiting for capture"
                        layoutActive -> "Text from the game's memory (Yomikata layout)"
                        builtIn == MemoryTextStatus.Locked -> "Text from the game's memory"
                        builtIn == MemoryTextStatus.Searching -> "Looking for this line in the game's memory…"
                        builtIn == MemoryTextStatus.Script && ocrOn -> "OCR, with the exact text from the game's memory"
                        azahar == MemoryTextStatus.Locked -> "Azahar text (following it in memory)"
                        azahar == MemoryTextStatus.Searching -> "Azahar: looking for this line in memory…"
                        azahar == MemoryTextStatus.Script && ocrOn -> "OCR, with the exact text from Azahar's memory"
                        ppsspp is PpssppStatus.Hooked -> "PPSSPP text (${(ppsspp as PpssppStatus.Hooked).gameId})"
                        ppsspp is PpssppStatus.NoProfile -> "PPSSPP: no text hook for ${(ppsspp as PpssppStatus.NoProfile).gameId} yet; try OCR, or Find hook"
                        ppsspp == PpssppStatus.Waiting && !ocrOn -> "Waiting for PPSSPP's debugger"
                        ocrOn -> engine.name + (ocrMs?.let { "  ·  $it ms" } ?: "") + (if (recording) "  ·  audio" else "") +
                            (if (azahar == MemoryTextStatus.NotFound) "  ·  not found in Azahar's memory" else "") +
                            (if (builtIn == MemoryTextStatus.NotFound) "  ·  not found in the game's memory" else "")
                        azahar == MemoryTextStatus.NeedsOcr -> "Azahar: turn OCR on once to find the text"
                        builtIn == MemoryTextStatus.NeedsOcr -> "Built-in player: turn OCR on once to find the game's text"
                        else -> when (status) {
                            is HookStatus.Connected -> "Texthooker connected"
                            is HookStatus.Waiting -> "Waiting for a texthooker"
                        }
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            // The chips take what they need, up to most of the bar, and scroll sideways past that;
            // the game's title gets the rest.
            Row(
                Modifier.widthIn(max = LocalConfiguration.current.screenWidthDp.dp * 0.6f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                onShowGame?.let { Chip("Game", it) }
                Chip(if (ocrOn) "OCR on" else "OCR") { viewModel.toggleOcr(onAskCaptureConsent) }
                if (ocrOn && capturing) Chip("Area") { viewModel.editArea() }
                if (ocrOn) Chip(engine.name) { viewModel.ocr.nextEngine() }
                // Sentence audio rides on the capture; it needs the recording permission once.
                if (capturing && !recording) Chip("Audio") { onAskAudioPermission() }
                // The hook finder, for a PSP game without a hook; it needs OCR to compare lines with.
                when (finder) {
                    is HookFinderState.Working -> Chip("Stop") { viewModel.hookFinder.stop() }
                    is HookFinderState.Found, is HookFinderState.Failed -> Chip("OK") { viewModel.hookFinder.dismiss() }
                    HookFinderState.Idle -> if (ppsspp is PpssppStatus.NoProfile) Chip("Find hook") { viewModel.hookFinder.start() }
                }
                // A snapshot now, for Yomikata (a line typing out, or waiting for a press).
                if (playing && sampling) Chip("Snapshot") { viewModel.snapshotNow() }
                if (threads.isNotEmpty() || chosen != null) {
                    Chip("Thread: ${chosen?.thread ?: "choose"}") { viewModel.openPicker() }
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val panelHeight = maxHeight * PANEL_FRACTION
            val needsChoice = threads.isNotEmpty() && chosen == null
            if (editing != null) {
                OcrAreaEditor(editing, region, onSave = viewModel::saveArea, onReset = viewModel::resetArea, onCancel = viewModel::closeArea)
            } else if (picking || needsChoice) {
                ThreadPicker(threads, chosen?.thread, onChoose = viewModel::choose, onClose = viewModel::closePicker.takeIf { !needsChoice })
            } else {
                Backlog(lines, lookup, panelHeight, marks, onTap = viewModel::lookUp, onBackground = viewModel::closeLookup)
            }
            lookup?.let { current ->
                LookupPanel(
                    current,
                    popup,
                    viewModel,
                    onClose = viewModel::closeLookup,
                    modifier = Modifier.align(Alignment.BottomCenter).height(panelHeight),
                )
            }
        }
    }
}

@Composable
internal fun Chip(label: String, onClick: () -> Unit) {
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

/** A speaker known only by number ("#7"): kept to break runs, not shown. */
private val UNNAMED_SPEAKER = Regex("#\\d+")

@Composable
private fun Backlog(
    lines: List<ReaderLineEntity>,
    lookup: Lookup?,
    panelHeight: Dp,
    marks: Map<Long, List<LineMark>>,
    onTap: (ReaderLineEntity, Int) -> Unit,
    onBackground: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val lookupLine = lookup?.lineId
    // Follow new lines as they arrive, unless a word is being looked up. Keyed on the newest
    // line, not the count: the backlog holds a fixed number, so a full one stays the same size.
    LaunchedEffect(lines.lastOrNull()?.id) {
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
        itemsIndexed(lines, key = { _, line -> line.id }) { i, line ->
            val highlight = lookup?.takeIf { it.lineId == line.id }
            // A run: lines said in a row by one speaker (or by none). A new run shows its speaker's
            // name above it, once; one without a name to show (narration, or a speaker the catalog
            // only numbers, stored as "#n") is set apart by space instead.
            val speaker = line.speaker
            val newRun = i > 0 && lines[i - 1].speaker != speaker
            val name = speaker?.takeUnless { UNNAMED_SPEAKER.matches(it) }
            Column(Modifier.padding(top = if (newRun && name == null) 10.dp else 0.dp)) {
                if (name != null && (i == 0 || newRun)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                TappableLine(line.text, highlight?.start, highlight?.end, marks[line.id].orEmpty()) { onTap(line, it) }
            }
        }
    }
}

/** A line whose characters can be tapped; the looked-up word is highlighted. */
@Composable
private fun TappableLine(text: String, start: Int?, end: Int?, marks: List<LineMark>, onTapAt: (Int) -> Unit) {
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
        modifier = Modifier.fillMaxWidth().drawBehind { layout.value?.let { drawMarks(it, marks, text.length) } }.pointerInput(text) {
            detectTapGestures { position ->
                layout.value?.let { onTapAt(it.getOffsetForPosition(position).coerceAtMost(text.length - 1)) }
            }
        },
    )
}

/**
 * Hachidori's word marks under the words: solid for a word not in Anki, dashed for one being
 * learned, in its default colours. Drawn from the layout, since text styles can't dash a line.
 */
private fun DrawScope.drawMarks(layout: TextLayoutResult, marks: List<LineMark>, length: Int) {
    val stroke = 2.dp.toPx()
    for (mark in marks) {
        val end = mark.end.coerceAtMost(length)
        if (mark.start >= end) continue
        val color = if (mark.state == WordMarkState.NOT_IN_ANKI) NOT_IN_ANKI else LEARNING
        val effect = if (mark.state == WordMarkState.LEARNING) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null
        // One stroke per visual line the word spans.
        (mark.start until end).groupBy { layout.getLineForOffset(it) }.forEach { (line, offsets) ->
            val left = offsets.minOf { layout.getBoundingBox(it).left }
            val right = offsets.maxOf { layout.getBoundingBox(it).right }
            val y = layout.getLineBottom(line) - stroke * 2
            drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = stroke, pathEffect = effect)
        }
    }
}

/** Hachidori's default mark colours. */
private val NOT_IN_ANKI = Color(0xFFC67D80)
private val LEARNING = Color(0xFFC29A65)

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
private fun LookupPanel(lookup: Lookup, popup: PopupContent?, viewModel: ReaderViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(colors.surface)
            .padding(top = 12.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
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
        DictionaryPopup(popup, viewModel.dictionaries, viewModel, Modifier.fillMaxSize())
    }
}

/** Share of the reader the lookup panel takes. */
private const val PANEL_FRACTION = 0.55f
