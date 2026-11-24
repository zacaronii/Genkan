package app.genkan.words

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.genkan.dictionary.Dictionaries
import javax.inject.Inject
import javax.inject.Singleton

/** How the popup looks up and shows a word: Genkan's own settings, or a linked Hachidori host's. */
data class PopupPrefs(
    val counts: Boolean = true,
    val summary: Boolean = false,
    val summaryCount: Int = 3,
    /** The dictionary brief definitions come from first, by title (null: the results' order). */
    val summaryDictionary: String? = null,
    val maxResults: Int = LOCAL_MAX_RESULTS,
    /** The frequency dictionary results are sorted by, by title (null: the first one enabled). */
    val frequencyDictionary: String? = null,
    /** The host's frequency order ("ascending", "descending", "disabled" override; "auto" or null: by the dictionary's mode). */
    val frequencyOrder: String? = null,
    /** Whether these came from a Hachidori host. */
    val fromHachidori: Boolean = false,
) {
    companion object {
        const val LOCAL_MAX_RESULTS = 16
    }
}

/**
 * The popup's settings now. While a Hachidori host is linked its reader options apply (the ones
 * a lookup client needs, as Amatsubame's apps take them), so the popup reads as Hachidori's does,
 * offline too; Genkan's own apply otherwise. A key the host left out has Hachidori's default.
 */
@Singleton
class PopupPrefsSource @Inject constructor(settings: WordSettings, link: HachidoriLink, dictionaries: Dictionaries) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val prefs: StateFlow<PopupPrefs> = combine(
        settings.counts, settings.summary, settings.summaryCount, link.hostOptions,
    ) { counts, summary, count, host ->
        if (host == null) {
            PopupPrefs(counts = counts, summary = summary, summaryCount = count)
        } else {
            PopupPrefs(
                counts = host.optBoolean("showLookupCounts", true),
                summary = host.optBoolean("showCompactDefinitionSummary", false),
                summaryCount = host.optInt("compactDefinitionSummaryCount", 3).coerceIn(1, 6),
                summaryDictionary = host.optString("compactDefinitionSummaryDictionary").takeIf { it.isNotBlank() && it != "null" },
                maxResults = host.optInt("maxResults", HACHIDORI_MAX_RESULTS).coerceIn(1, 64),
                frequencyDictionary = host.optString("frequencyDictionary").takeIf { it.isNotBlank() && it != "null" },
                frequencyOrder = host.optString("frequencyOrder").takeIf { it.isNotBlank() && it != "null" },
                fromHachidori = true,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, PopupPrefs())

    init {
        scope.launch {
            prefs.collect {
                dictionaries.preferredFrequency = it.frequencyDictionary
                dictionaries.preferredFrequencyOrder = it.frequencyOrder
            }
        }
    }

    private companion object {
        const val HACHIDORI_MAX_RESULTS = 32
    }
}
