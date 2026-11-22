package app.genkan.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.manual.ManualText
import app.genkan.ocr.ScreenText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** The lookup's line id for a word on a manual page: its own, so nothing in the backlog lights up. */
const val MANUAL_LINE = -2L

/** The word being looked up on a manual: on [page], where OCR read it. */
data class ManualHit(val page: Int, val hit: ScreenText.Hit)

/**
 * Taps on the playing game's manual: each reads its page by OCR on the page's first tap (the bar
 * says so meanwhile) and hands the word under it to [lookUp], which answers whether a lookup
 * started. Off every line, or when the page can't be read, the bar says so for a moment.
 */
class ManualTaps(
    private val text: ManualText,
    private val scope: CoroutineScope,
    private val lookUp: (text: String, offset: Int) -> Boolean,
) {
    private val _status = MutableStateFlow<String?>(null)
    /** What a tap is doing, for the manual's bar: reading the page, or why nothing was looked up. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _onTop = MutableStateFlow(false)
    /**
     * Whether the lookup's panel opens at the top: decided by the tap that started the lookup
     * ([panelOnTop]), so a tap that misses leaves an open panel where it is.
     */
    val onTop: StateFlow<Boolean> = _onTop.asStateFlow()

    private val _hit = MutableStateFlow<ManualHit?>(null)
    /** Where the word being looked up is, to box it there; null once that lookup is gone. */
    val hit: StateFlow<ManualHit?> = _hit.asStateFlow()

    private var job: Job? = null

    /**
     * A tap on [page] of [file] at ([x], [y]), fractions of the page, rendered [widthPx] wide to
     * read it; [tapY] is how far down the manual it was on screen, for the panel's side.
     */
    fun tap(file: File, pages: ManualPages, page: Int, x: Float, y: Float, widthPx: Int, tapY: Float = 0f) {
        job?.cancel()
        job = scope.launch {
            if (!text.isRead(file.path, page)) _status.value = READING
            val hit = try {
                text.hit(file.path, pages, page, x, y, widthPx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read page ${page + 1} of the manual", e)
                return@launch show(COULD_NOT_READ)
            }
            if (hit == null) return@launch show(NO_TEXT)
            _status.value = null
            if (lookUp(hit.text, hit.offset)) {
                _onTop.value = panelOnTop(tapY)
                _hit.value = ManualHit(page, hit)
            }
        }
    }

    /** Shows [message] in the bar for a moment, in place of whatever a tap was saying. */
    fun say(message: String) {
        job?.cancel()
        job = scope.launch { show(message) }
    }

    private suspend fun show(message: String) {
        _status.value = message
        delay(STATUS_MS)
        // Only what this put there: a newer message stays.
        if (_status.value == message) _status.value = null
    }

    /** The word's lookup is gone (closed, or another one started). */
    fun clearHit() {
        _hit.value = null
    }

    /** The manual went: nothing more to say in its bar. */
    fun closed() {
        job?.cancel()
        _status.value = null
        _hit.value = null
    }

    companion object {
        const val READING = "Reading this page…"
        const val NO_TEXT = "No text there"
        const val COULD_NOT_READ = "Couldn't read this page"
        const val STATUS_MS = 2_500L
        private const val TAG = "ManualTaps"
    }
}

/**
 * The box over the word being looked up on the manual: the tapped character until the
 * dictionaries answer, then the longest match; nothing unless [lookup] is the manual's.
 */
fun manualMark(hit: ManualHit?, lookup: Lookup?): ManualMark? {
    if (hit == null || lookup == null || lookup.lineId != MANUAL_LINE) return null
    val line = hit.hit.line ?: return null
    val end = if (lookup.result != null && lookup.end > lookup.start) lookup.end else lookup.start + 1
    return ManualMark(hit.page, line.span(lookup.start, end))
}

/** Share of the manual the lookup's panel takes: under half, so the tapped line stays clear of it either way. */
const val MANUAL_PANEL_FRACTION = 0.42f

/**
 * Whether the panel opens at the top: when the tap was in the lower half of the manual ([tapY],
 * a fraction of its height on screen), so the panel never covers the tapped line, zoomed or not.
 */
fun panelOnTop(tapY: Float): Boolean = tapY >= 0.5f

/**
 * The manual with a word's lookup over it: [manual] fills the screen, and while [lookup] is open
 * its panel takes [MANUAL_PANEL_FRACTION] of the height at the top or the bottom ([onTop]), with
 * [popup] inside. A panel at the top covers the manual's bar, so its [status] shows just below
 * the panel instead. Back closes the panel first, then (the manual's own) the manual.
 */
@Composable
fun ManualWithLookup(
    lookup: Lookup?,
    onTop: Boolean,
    onCloseLookup: () -> Unit,
    popup: @Composable () -> Unit,
    status: String? = null,
    manual: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        manual()
        lookup?.let { current ->
            // Added after the manual's, so it's the one Back reaches first.
            BottomBackHandler(onBack = onCloseLookup)
            LookupPanel(
                current,
                onClose = onCloseLookup,
                modifier = Modifier
                    .align(if (onTop) Alignment.TopCenter else Alignment.BottomCenter)
                    .height(maxHeight * MANUAL_PANEL_FRACTION),
                atTop = onTop,
                popup = popup,
            )
            if (onTop && status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = maxHeight * MANUAL_PANEL_FRACTION + 8.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}
