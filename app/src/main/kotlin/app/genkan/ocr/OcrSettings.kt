package app.genkan.ocr

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The OCR engines Genkan can read the screen with. */
enum class OcrEngineChoice { MEIKI, ML_KIT }

private val Context.ocrStore: DataStore<Preferences> by preferencesDataStore(name = "ocr")

/** Which engine OCR uses. Meiki unless chosen otherwise. */
@Singleton
class OcrSettings @Inject constructor(@ApplicationContext private val context: Context) {
    val engine: Flow<OcrEngineChoice> = context.ocrStore.data.map { prefs ->
        prefs[ENGINE]?.let { runCatching { OcrEngineChoice.valueOf(it) }.getOrNull() } ?: OcrEngineChoice.MEIKI
    }

    suspend fun setEngine(choice: OcrEngineChoice) {
        context.ocrStore.edit { it[ENGINE] = choice.name }
    }

    private companion object {
        val ENGINE = stringPreferencesKey("engine")
    }
}
