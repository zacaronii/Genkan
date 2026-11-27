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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.core.CoreCatalog
import app.genkan.core.TuningLadders
import app.genkan.core.TuningStore
import app.genkan.library.LibraryDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One game with a step of its own: its name, the step as its ladder labels it, and why it was set. */
data class TuningEntry(val romId: Long, val title: String, val step: String, val why: String)

/** Settings › Advanced › Tuning steps: the games with a step of their own, each cleared with a press. */
@HiltViewModel
class TuningStepsViewModel @Inject constructor(
    private val store: TuningStore,
    private val database: LibraryDatabase,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<TuningEntry>>(emptyList())
    val entries: StateFlow<List<TuningEntry>> = _entries.asStateFlow()

    private val pressAgain = PressAgain()
    private val _clearAllArmed = MutableStateFlow(false)
    /** Clear all waits for its second press. */
    val clearAllArmed: StateFlow<Boolean> = _clearAllArmed.asStateFlow()

    /** Reads the list again (each time the page opens). */
    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            _entries.value = store.all().map { (romId, step) ->
                val game = database.library().game(romId)
                val ladder = game?.let { CoreCatalog.forPlatform(it.platformSlug) }?.let { TuningLadders.of(it.tuning) }
                TuningEntry(romId, game?.name ?: "Game $romId", ladder?.step(step.step)?.label ?: step.step, step.why)
            }.sortedBy { it.title }
        }
    }

    fun clear(romId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            store.clearOverride(romId)
            load()
        }
    }

    /** Clear all, on its second press within [PressAgain.WINDOW_MS]. */
    fun clearAll() {
        if (!pressAgain.press(CLEAR_ALL, System.currentTimeMillis())) {
            _clearAllArmed.value = true
            viewModelScope.launch {
                delay(PressAgain.WINDOW_MS)
                _clearAllArmed.value = false
            }
            return
        }
        _clearAllArmed.value = false
        viewModelScope.launch(Dispatchers.IO) {
            store.clearAll()
            load()
        }
    }

    private companion object {
        const val CLEAR_ALL = "clear all"
    }
}

@Composable
fun TuningStepsScreen(viewModel: TuningStepsViewModel) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val armed by viewModel.clearAllArmed.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    TuningStepsContent(entries, armed, onClear = viewModel::clear, onClearAll = viewModel::clearAll)
}

@Composable
fun TuningStepsContent(entries: List<TuningEntry>, clearAllArmed: Boolean, onClear: (Long) -> Unit, onClearAll: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val first = remember { FocusRequester() }
    LaunchedEffect(entries.isEmpty()) { runCatching { first.requestFocus() } }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Tuning steps", style = MaterialTheme.typography.displaySmall, color = colors.primary)
        Text(
            "Games that play at a step of their own: chosen on their details, or learned as they were played. A press " +
                "clears one, so it plays at its console's step again.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        HintRow(Hints.form())
        Column(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (entries.isEmpty()) {
                Text("No game has a step of its own.", style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
            } else {
                ActionRow(
                    if (clearAllArmed) "Press again to clear all ${entries.size}" else "Clear all ${entries.size}",
                    focused = null,
                    onClick = onClearAll,
                    modifier = Modifier.focusRequester(first),
                )
                entries.forEach { entry ->
                    ActionRow("${entry.title}: ${entry.step}  ·  ${entry.why}", focused = null, onClick = { onClear(entry.romId) })
                }
            }
        }
    }
}
