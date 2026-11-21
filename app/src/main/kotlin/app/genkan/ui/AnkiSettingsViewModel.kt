package app.genkan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.anki.AnkiConfig
import app.genkan.anki.AnkiConnect
import app.genkan.anki.AnkiMiner
import app.genkan.words.WordMarks
import app.genkan.anki.AnkiSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The decks and note types the connected Anki has, for the pickers. */
data class AnkiChoices(val decks: List<String>, val noteTypes: List<String>)

@HiltViewModel
class AnkiSettingsViewModel @Inject constructor(
    private val settings: AnkiSettings,
    private val anki: AnkiConnect,
    val miner: AnkiMiner,
    private val wordMarks: WordMarks,
) : ViewModel() {

    private val _form = MutableStateFlow(AnkiConfig())
    val form: StateFlow<AnkiConfig> = _form.asStateFlow()

    private val _check = MutableStateFlow<String?>(null)
    /** What the last Save and test found. */
    val check: StateFlow<String?> = _check.asStateFlow()

    private val _choices = MutableStateFlow<AnkiChoices?>(null)
    /** Null until Anki answers; the deck and note type are typed in until then. */
    val choices: StateFlow<AnkiChoices?> = _choices.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = settings.config.first()
            _form.value = saved
            if (saved.configured) runCatching { loadChoices(saved) }
        }
    }

    private suspend fun loadChoices(config: AnkiConfig): AnkiChoices =
        AnkiChoices(anki.deckNames(config).sorted(), anki.modelNames(config).sorted()).also { _choices.value = it }

    /** Word marks on or off, saved now. */
    fun toggleMarks() = saveMarks { it.copy(marksWords = !it.marksWords) }

    /** [deck] counted for word marks or not, saved now. */
    fun toggleMarkDeck(deck: String) = saveMarks { c -> c.copy(markDecks = if (deck in c.markDecks) c.markDecks - deck else c.markDecks + deck) }

    private fun saveMarks(change: (AnkiConfig) -> AnkiConfig) {
        viewModelScope.launch {
            val saved = change(settings.current())
            settings.save(saved)
            _form.value = _form.value.copy(marksWords = saved.marksWords, markDecks = saved.markDecks)
            wordMarks.refreshSoon()
        }
    }

    fun edit(change: (AnkiConfig) -> AnkiConfig) {
        _form.value = change(_form.value)
    }

    /** Saves the form, then asks AnkiConnect whether the deck and note type exist. */
    fun saveAndTest() {
        val config = _form.value
        viewModelScope.launch {
            settings.save(config)
            if (!config.configured) {
                _check.value = "Saved. Cards wait in the outbox until an AnkiConnect address is set."
                return@launch
            }
            _check.value = "Checking ${config.url}…"
            _check.value = try {
                val version = anki.version(config)
                val choices = loadChoices(config)
                val problems = buildList {
                    if (config.deck !in choices.decks) add("no deck named \"${config.deck}\"; choose one above")
                    if (config.noteType !in choices.noteTypes) add("no note type named \"${config.noteType}\"; choose one above")
                }
                if (problems.isEmpty()) {
                    val fields = anki.modelFieldNames(config, config.noteType)
                    miner.send()
                    "Connected to AnkiConnect $version. ${config.noteType} has ${fields.size} fields; cards go to ${config.deck}."
                } else {
                    "Connected, but Anki has ${problems.joinToString(" and ")}."
                }
            } catch (e: Exception) {
                "Couldn't reach AnkiConnect at ${config.url}: ${e.message ?: e.javaClass.simpleName}. " +
                    "Is Anki open, and is AnkiConnect's webBindAddress 0.0.0.0?"
            }
        }
    }
}
