package app.genkan.anki

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Where cards go: an AnkiConnect server, the deck and the note type. */
data class AnkiConfig(
    val address: String = "",
    val apiKey: String = "",
    val deck: String = "Mining",
    val noteType: String = "Lapis",
) {
    val configured: Boolean get() = address.isNotBlank()

    /**
     * The address as AnkiConnect expects it. A bare host gets http and AnkiConnect's port 8765; a
     * full URL is used as written, since one behind a reverse proxy has the scheme's own port.
     */
    val url: String
        get() {
            val trimmed = address.trim().removeSuffix("/")
            if ("://" in trimmed) return trimmed
            return if (':' in trimmed.substringBefore('/')) "http://$trimmed" else "http://$trimmed:8765"
        }
}

private val Context.ankiStore: DataStore<Preferences> by preferencesDataStore(name = "anki")

@Singleton
class AnkiSettings @Inject constructor(@ApplicationContext private val context: Context) {

    val config: Flow<AnkiConfig> = context.ankiStore.data.map {
        AnkiConfig(
            address = it[ADDRESS].orEmpty(),
            apiKey = it[API_KEY].orEmpty(),
            deck = it[DECK] ?: AnkiConfig().deck,
            noteType = it[NOTE_TYPE] ?: AnkiConfig().noteType,
        )
    }

    suspend fun current(): AnkiConfig = config.first()

    suspend fun save(config: AnkiConfig) {
        context.ankiStore.edit {
            it[ADDRESS] = config.address.trim()
            it[API_KEY] = config.apiKey.trim()
            it[DECK] = config.deck.trim()
            it[NOTE_TYPE] = config.noteType.trim()
        }
    }

    private companion object {
        val ADDRESS = stringPreferencesKey("address")
        val API_KEY = stringPreferencesKey("apiKey")
        val DECK = stringPreferencesKey("deck")
        val NOTE_TYPE = stringPreferencesKey("noteType")
    }
}
