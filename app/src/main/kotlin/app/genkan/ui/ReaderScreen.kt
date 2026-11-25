package app.genkan.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.genkan.manual.ManualFetch
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
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
    /** The headphones list, which the menu opens over the reader. */
    headphones: HeadphonesViewModel? = null,
) {
    val lines by viewModel.session.lines.collectAsStateWithLifecycle()
    val status by viewModel.session.status.collectAsStateWithLifecycle()
    val game by viewModel.session.game.collectAsStateWithLifecycle()
    val threads by viewModel.session.threads.collectAsStateWithLifecycle()
    val chosen by viewModel.session.chosenThread.collectAsStateWithLifecycle()
    val picking by viewModel.picking.collectAsStateWithLifecycle()
    // A word looked up on the manual has its panel over the manual; the reader leaves it alone.
    val lookup = viewModel.lookup.collectAsStateWithLifecycle().value?.takeIf { it.lineId != MANUAL_LINE }
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
    val scriptActive by viewModel.builtIn.scriptActive.collectAsStateWithLifecycle()
    val playing by viewModel.builtIn.playing.collectAsStateWithLifecycle()
    val sampling by viewModel.builtIn.sampling.collectAsStateWithLifecycle(false)
    val azahar by viewModel.azahar.status.collectAsStateWithLifecycle()
    val armsx by viewModel.armsx.status.collectAsStateWithLifecycle()
    val builtIn by viewModel.builtIn.status.collectAsStateWithLifecycle()
    val notListed by viewModel.builtIn.notListed.collectAsStateWithLifecycle()
    val overlayOn by viewModel.overlay.enabled.collectAsStateWithLifecycle()
    val overlayAvailable by viewModel.overlay.available.collectAsStateWithLifecycle()
    val look by viewModel.look.collectAsStateWithLifecycle()
    val choice by viewModel.builtIn.choice.collectAsStateWithLifecycle()
    val manualGame by viewModel.manual.game.collectAsStateWithLifecycle()
    val manualOpen by viewModel.manual.open.collectAsStateWithLifecycle()
    val manualNotice by viewModel.manual.notice.collectAsStateWithLifecycle()
    val manualFetch by viewModel.manual.fetch.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val menuItems = ReaderMenu.items(
        ReaderBarState(
            canShowGame = onShowGame != null,
            manual = manualGame != null && manualOpen == null,
            manualFetch = manualFetch,
            ocrOn = ocrOn,
            capturing = capturing,
            engine = engine.name,
            recording = recording,
            finder = finder,
            canFindHook = ppsspp is PpssppStatus.NoProfile,
            overlayAvailable = overlayAvailable,
            overlayOn = overlayOn,
            look = look?.let { (now, kept) -> now.label to kept },
            hasThreads = threads.isNotEmpty(),
            thread = chosen?.thread,
            headphones = headphones != null,
        ),
    )
    var menuOpen by remember { mutableStateOf(false) }
    var headphonesOpen by remember { mutableStateOf(false) }
    if (menuItems.isEmpty()) menuOpen = false
    val pick: (ReaderMenuItem) -> Unit = { item ->
        if (item.closes) menuOpen = false
        when (item.action) {
            ReaderAction.GAME -> onShowGame?.invoke()
            ReaderAction.MANUAL -> viewModel.manual.show()
            ReaderAction.OCR_AREA -> viewModel.editArea()
            ReaderAction.OCR_ENGINE -> viewModel.ocr.nextEngine()
            ReaderAction.AUDIO -> onAskAudioPermission()
            ReaderAction.FIND_HOOK -> viewModel.hookFinder.start()
            ReaderAction.STOP_HOOK -> viewModel.hookFinder.stop()
            ReaderAction.HOOK_DONE -> viewModel.hookFinder.dismiss()
            ReaderAction.OVERLAY -> viewModel.overlay.toggle()
            ReaderAction.LOOK -> viewModel.openLookPicker()
            ReaderAction.KEEP_LOOK -> viewModel.keepLook()
            ReaderAction.THREAD -> viewModel.openPicker()
            ReaderAction.HEADPHONES -> headphonesOpen = true
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        // While a word's popup is up, a tap on the bar only closes it, and goes no further.
        TapCloses(active = lookup != null, onClose = viewModel::closeLookup) {
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
                            manualNotice != null -> manualNotice!!
                            finder is HookFinderState.Working -> (finder as HookFinderState.Working).message
                            finder is HookFinderState.Found -> (finder as HookFinderState.Found).message
                            finder is HookFinderState.Failed -> (finder as HookFinderState.Failed).message
                            ocrOn && !capturing -> "OCR: waiting for capture"
                            scriptActive -> "Text from the game's memory (Yomikata script)"
                            layoutActive -> "Text from the game's memory (Yomikata layout)"
                            builtIn == MemoryTextStatus.Locked -> "Text from the game's memory"
                            builtIn == MemoryTextStatus.Searching -> "Looking for this line in the game's memory…"
                            builtIn == MemoryTextStatus.Script && ocrOn -> "OCR, with the exact text from the game's memory"
                            azahar == MemoryTextStatus.Locked -> "Azahar text (following it in memory)"
                            azahar == MemoryTextStatus.Searching -> "Azahar: looking for this line in memory…"
                            azahar == MemoryTextStatus.Script && ocrOn -> "OCR, with the exact text from Azahar's memory"
                            armsx == MemoryTextStatus.Locked -> "ARMSX2 text (following it in memory)"
                            armsx == MemoryTextStatus.Searching -> "ARMSX2: looking for this line in memory…"
                            armsx == MemoryTextStatus.Script && ocrOn -> "OCR, with the exact text from ARMSX2's memory"
                            ppsspp is PpssppStatus.Hooked -> "PPSSPP text (${(ppsspp as PpssppStatus.Hooked).gameId})"
                            ppsspp is PpssppStatus.NoProfile -> "PPSSPP: no text hook for ${(ppsspp as PpssppStatus.NoProfile).gameId} yet; try OCR, or Find hook"
                            ppsspp == PpssppStatus.Waiting && !ocrOn -> "Waiting for PPSSPP's debugger"
                            ocrOn -> engine.name + (ocrMs?.let { "  ·  $it ms" } ?: "") + (if (recording) "  ·  audio" else "") +
                                (if (azahar == MemoryTextStatus.NotFound) "  ·  not found in Azahar's memory" else "") +
                                (if (armsx == MemoryTextStatus.NotFound) "  ·  not found in ARMSX2's memory" else "") +
                                (if (builtIn == MemoryTextStatus.NotFound) "  ·  not found in the game's memory" else "") +
                                (if (notListed) "  ·  no text reader for this game yet" else "")
                            notListed -> "No text reader for this game yet; OCR is available"
                            azahar == MemoryTextStatus.NeedsOcr -> "Azahar: turn OCR on once to find the text"
                            armsx == MemoryTextStatus.NeedsOcr -> "ARMSX2: turn OCR on once to find the text"
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
                // OCR and Snapshot stay on the bar, one press away; the rest is in the menu, each
                // row with its state. The chips take what they need, up to most of the bar.
                Row(
                    Modifier.widthIn(max = LocalConfiguration.current.screenWidthDp.dp * 0.6f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Chip(if (ocrOn) "OCR on" else "OCR") { viewModel.toggleOcr(onAskCaptureConsent) }
                    // A snapshot now, for Yomikata (a line typing out, or waiting for a press).
                    if (playing && sampling) Chip("Snapshot") { viewModel.snapshotNow() }
                    if (menuItems.isNotEmpty()) {
                        ReaderMenuButton(menuOpen, attention = menuItems.any { it.attention }) { menuOpen = !menuOpen }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val panelHeight = maxHeight * PANEL_FRACTION
            val choiceHeight = maxHeight * CHOICE_FRACTION
            val needsChoice = threads.isNotEmpty() && chosen == null
            if (editing != null) {
                OcrAreaEditor(editing, region, onSave = viewModel::saveArea, onReset = viewModel::resetArea, onCancel = viewModel::closeArea)
            } else if (picking || needsChoice) {
                ThreadPicker(threads, chosen?.thread, onChoose = viewModel::choose, onClose = viewModel::closePicker.takeIf { !needsChoice })
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        // With a choice's list under it, the lookup panel's room is kept below the list instead.
                        Backlog(
                            lines, lookup, if (choice == null) panelHeight else 0.dp, marks,
                            onTap = viewModel::lookUp, onBackground = viewModel::closeLookup, follow = choice != null,
                        )
                    }
                    // A choice on screen: its answers under the backlog, until one is taken; a
                    // word in one is looked up as in a line.
                    choice?.let {
                        ChoiceList(
                            it.answers, it.cursor,
                            lookup = lookup,
                            onTapAt = viewModel::lookUpAnswer,
                            modifier = Modifier.heightIn(max = choiceHeight),
                        )
                        if (lookup != null) androidx.compose.foundation.layout.Spacer(Modifier.height(panelHeight))
                    }
                }
            }
            lookup?.let { current ->
                LookupPanel(
                    current,
                    onClose = viewModel::closeLookup,
                    modifier = Modifier.align(Alignment.BottomCenter).height(panelHeight),
                ) { DictionaryPopup(popup, viewModel.dictionaries, viewModel, Modifier.fillMaxSize()) }
            }
            if (menuOpen) {
                BottomBackHandler { menuOpen = false }
                // A tap outside the menu closes it, and goes no further.
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { menuOpen = false },
                )
                ReaderMenuPanel(menuItems, onPick = pick, modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 4.dp))
            }
            if (headphonesOpen && headphones != null) {
                // The list stops following Bluetooth and forgets its last word as it leaves.
                val close = { headphonesOpen = false }
                BottomBackHandler(onBack = close)
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = close,
                    ),
                )
                HeadphonesPanel(headphones, Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 4.dp).widthIn(max = 420.dp))
            }
        }
    }
}

/**
 * A choice's [answers] as the game lists them, the row the game's cursor is on ([cursor]) marked
 * and kept in view. A tap on a word in an answer looks it up ([onTapAt]: the answer's index and
 * the offset); the looked-up span ([lookup], on [answerLine]) is highlighted.
 */
@Composable
fun ChoiceList(
    answers: List<String>,
    cursor: Int?,
    modifier: Modifier = Modifier,
    lookup: Lookup? = null,
    onTapAt: (Int, String, Int) -> Unit = { _, _, _ -> },
) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    LaunchedEffect(cursor, answers.size) {
        cursor?.takeIf { it in answers.indices }?.let { listState.animateScrollToItem(it) }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().background(colors.surface),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(answers) { i, answer ->
            val on = i == cursor
            val span = lookup?.takeIf { it.lineId == answerLine(i) && it.lineText == answer }
            val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
            val text = buildAnnotatedString {
                val start = span?.start
                val end = span?.end?.coerceAtMost(answer.length)
                if (start == null || end == null || end <= start) {
                    append(answer)
                } else {
                    append(answer.substring(0, start))
                    withStyle(SpanStyle(background = colors.primary.copy(alpha = 0.35f))) { append(answer.substring(start, end)) }
                    append(answer.substring(end))
                }
            }
            BasicText(
                text,
                style = MaterialTheme.typography.titleMedium.copy(color = if (on) colors.onPrimaryContainer else colors.onSurface),
                onTextLayout = { layout.value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) colors.primaryContainer else Color.Transparent)
                    .pointerInput(answer) {
                        detectTapGestures { position ->
                            layout.value?.let { onTapAt(i, answer, it.getOffsetForPosition(position).coerceAtMost(answer.length - 1)) }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** The reader's Manual chip: while the manual is fetched, a ring and no presses; after a failure, why. */
@Composable
private fun ManualChip(fetch: ManualFetch?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.secondaryContainer)
            .clickable(enabled = fetch !is ManualFetch.Busy, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (fetch is ManualFetch.Busy) BusyRing(fetch.progress, colors.onSecondaryContainer, size = 14.dp)
        Text(
            when (fetch) {
                is ManualFetch.Failed -> fetch.message
                else -> "Manual"
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (fetch is ManualFetch.Failed) colors.error else colors.onSecondaryContainer,
        )
    }
}

@Composable
internal fun Chip(label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    // Marked as the look list's rows are, so the pad shows where it is; touch never focuses it.
    val focused by interaction.collectIsFocusedAsState()
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = colors.onSecondaryContainer,
        modifier = Modifier
            .clip(shape)
            .background(colors.secondaryContainer)
            .border(2.dp, if (focused) colors.primary else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The catcher over the reader's bar while a popup is up. */
internal const val BAR_CLOSES_POPUP = "bar-closes-popup"

/** [content], and while [active] a cover over it: a tap there only calls [onClose], and goes no further. */
@Composable
internal fun TapCloses(active: Boolean, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box {
        content()
        if (active) {
            Box(
                Modifier.matchParentSize().testTag(BAR_CLOSES_POPUP).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
            )
        }
    }
}

/** The size of a line's furigana. */
private val RUBY_SIZE = 13.sp

/** A speaker known only by number ("#7"): kept to break runs, not shown. */
private val UNNAMED_SPEAKER = Regex("#\\d+")

/**
 * The backlog's lines: a tap on a character looks it up ([onTap]); any other tap on it (past a
 * short line's end, between lines, below the last) is [onBackground], which closes the popup.
 */
@Composable
internal fun Backlog(
    lines: List<ReaderLineEntity>,
    lookup: Lookup?,
    panelHeight: Dp,
    marks: Map<Long, List<LineMark>>,
    onTap: (ReaderLineEntity, Int) -> Unit,
    onBackground: () -> Unit,
    /** Changes when the space for the backlog does (a choice's list comes or goes): the newest line is brought back into view. */
    follow: Any? = null,
) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val lookupLine = lookup?.lineId
    // Follow new lines as they arrive, unless a word is being looked up. Keyed on the newest
    // line, not the count: the backlog holds a fixed number, so a full one stays the same size.
    LaunchedEffect(lines.lastOrNull()?.id, follow) {
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
                TappableLine(
                    line.text, highlight?.start, highlight?.end, marks[line.id].orEmpty(), onMiss = onBackground,
                    ruby = remember(line.ruby) { app.genkan.reader.Ruby.fromJson(line.ruby) },
                ) { onTap(line, it) }
            }
        }
    }
}

/**
 * A line whose characters can be tapped; the looked-up word is highlighted. The line is as wide
 * as the backlog, so a tap that lands on no character (past a short line's end, in its padding)
 * is [onMiss], not the nearest character.
 */
@Composable
internal fun TappableLine(
    text: String,
    start: Int?,
    end: Int?,
    marks: List<LineMark>,
    onMiss: () -> Unit,
    /** The game's own furigana, drawn small over their characters. */
    ruby: List<app.genkan.reader.Ruby> = emptyList(),
    onTapAt: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val rubyStyle = androidx.compose.ui.text.TextStyle(color = colors.onBackground.copy(alpha = 0.8f), fontSize = RUBY_SIZE)
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
        // With furigana, each row is taller and its characters sit at the bottom, the readings above them.
        style = MaterialTheme.typography.headlineSmall.copy(color = colors.onBackground, fontSize = 26.sp, lineHeight = 38.sp).let {
            if (ruby.isEmpty()) it else it.copy(
                lineHeight = 52.sp,
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Bottom,
                    androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
                ),
            )
        },
        onTextLayout = { layout.value = it },
        modifier = Modifier.fillMaxWidth().drawBehind {
            layout.value?.let {
                drawMarks(it, marks, text.length)
                drawRuby(it, ruby, text.length, measurer, rubyStyle)
            }
        }.pointerInput(text) {
            detectTapGestures { position ->
                val result = layout.value ?: return@detectTapGestures
                val offset = result.getOffsetForPosition(position).coerceAtMost(text.length - 1)
                if (offset >= 0 && onCharacter(result, offset, position)) onTapAt(offset) else onMiss()
            }
        },
    )
}

/**
 * Each reading centred over its characters, at the top of their row (the room [TappableLine] makes
 * above them); over a word broken across rows, over its part on the first.
 */
private fun DrawScope.drawRuby(
    layout: TextLayoutResult,
    ruby: List<app.genkan.reader.Ruby>,
    length: Int,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: androidx.compose.ui.text.TextStyle,
) {
    for (r in ruby) {
        if (r.start < 0 || r.end > length || r.end <= r.start) continue
        val row = layout.getLineForOffset(r.start)
        val boxes = (r.start until r.end).filter { layout.getLineForOffset(it) == row }.map { layout.getBoundingBox(it) }
        val left = boxes.minOf { it.left }
        val right = boxes.maxOf { it.right }
        val reading = measurer.measure(r.reading, style)
        val x = ((left + right) / 2 - reading.size.width / 2).coerceIn(0f, (size.width - reading.size.width).coerceAtLeast(0f))
        drawText(reading, topLeft = Offset(x, layout.getLineTop(row) + 2.dp.toPx()))
    }
}

/** Whether [position] is on character [offset]'s box (as wide as the glyph, as tall as its row). */
internal fun onCharacter(layout: TextLayoutResult, offset: Int, position: Offset): Boolean {
    val box = layout.getBoundingBox(offset)
    val line = layout.getLineForOffset(offset)
    return position.x >= box.left && position.x <= box.right && position.y >= layout.getLineTop(line) && position.y <= layout.getLineBottom(line)
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
internal fun LookupPanel(
    lookup: Lookup,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** At the screen's top: its rounded corners go below. */
    atTop: Boolean = false,
    /** The dictionary's entries for the word, under the panel's header. */
    popup: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(if (atTop) RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp) else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
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
        popup()
    }
}

/** Share of the reader the lookup panel takes. */
private const val PANEL_FRACTION = 0.55f

/** The most of the reader's height a choice's list takes; past that it scrolls. */
private const val CHOICE_FRACTION = 0.33f
