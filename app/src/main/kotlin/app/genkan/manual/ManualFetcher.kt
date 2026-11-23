package app.genkan.manual

import app.genkan.library.GameEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Fetches games' manuals for one control (the details' "Open manual", the reader's Manual chip)
 * and keeps what it should show: nothing while a cached manual opens, [ManualFetch.Busy] with the
 * share done while one is fetched, [ManualFetch.Failed] for a few seconds after a fetch fails.
 * A cancelled fetch shows nothing: once [cancel]ed (or replaced), whatever it still reports from
 * its copy is ignored.
 */
class ManualFetcher(
    private val source: ManualSource,
    private val scope: CoroutineScope,
    /** What the control says when a fetch fails. */
    private val failedLabel: String,
) {
    private val _state = MutableStateFlow<ManualFetch?>(null)
    val state: StateFlow<ManualFetch?> = _state.asStateFlow()

    private var job: Job? = null

    /** The fetch allowed to write [state]; any other is cancelled or done. */
    @Volatile private var current = 0

    /**
     * Opens [game]'s manual: [onFile] gets the file, fetched first when it isn't cached. A press
     * while a fetch is under way does nothing; a press while a failure shows starts again at once.
     * [onFailed] gets why a fetch failed (a cancel isn't a failure).
     */
    fun open(game: GameEntity, onFile: (File) -> Unit, onFailed: (Throwable) -> Unit = {}) {
        if (job?.isActive == true && _state.value !is ManualFetch.Failed) return
        job?.cancel()
        val id = ++current
        fun show(state: ManualFetch?) {
            if (current == id) _state.value = state
        }
        show(null)
        job = scope.launch {
            if (source.cached(game) == null) show(ManualFetch.Busy())
            val file = try {
                source.file(game) { progress -> show(ManualFetch.Busy(progress)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show(ManualFetch.Failed(failedLabel))
                onFailed(e)
                delay(FAILED_MS)
                show(null)
                return@launch
            }
            show(null)
            onFile(file)
        }
    }

    /** Stops the fetch under way, if any: the control shows nothing, whatever the fetch still reports. */
    fun cancel() {
        current++
        _state.value = null
        job?.cancel()
    }

    companion object {
        /** How long a failure shows before the control is as it was. */
        const val FAILED_MS = 4_000L
    }
}
