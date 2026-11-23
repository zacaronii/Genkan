package app.genkan.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playerStore: DataStore<Preferences> by preferencesDataStore(name = "player")

/** Whether games the built-in player can run start there instead of in an installed emulator. */
@Singleton
class PlayerSettings @Inject constructor(@ApplicationContext private val context: Context) {

    val enabled: Flow<Boolean> = context.playerStore.data.map { it[ENABLED] == true }

    suspend fun isEnabled(): Boolean = enabled.first()

    suspend fun setEnabled(on: Boolean) {
        context.playerStore.edit { it[ENABLED] = on }
    }

    /** Whether lines missing from memory are saved as Yomikata samples ([app.genkan.memory.TextSamples]). */
    val samples: Flow<Boolean> = context.playerStore.data.map { it[SAMPLES] == true }

    suspend fun samplesEnabled(): Boolean = samples.first()

    suspend fun setSamples(on: Boolean) {
        context.playerStore.edit { it[SAMPLES] = on }
    }

    /**
     * The consoles (platform slugs) whose sound is played without asking for a low-latency stream.
     * Low latency is asked for by default, as before; a device that can't give it gets a plain
     * stream anyway (see the player's sound log).
     */
    val lowLatencyOff: Flow<Set<String>> = context.playerStore.data.map { it[LOW_LATENCY_OFF].orEmpty() }

    suspend fun lowLatency(platform: String): Boolean = platform !in lowLatencyOff.first()

    suspend fun setLowLatency(platform: String, on: Boolean) {
        context.playerStore.edit { prefs ->
            val off = prefs[LOW_LATENCY_OFF].orEmpty()
            prefs[LOW_LATENCY_OFF] = if (on) off - platform else off + platform
        }
    }

    /** The device's A is its right button (Nintendo layout); no: the bottom one (Xbox layout, as before). */
    val aOnRight: Flow<Boolean> = context.playerStore.data.map { it[A_ON_RIGHT] == true }

    suspend fun setAOnRight(on: Boolean) {
        context.playerStore.edit { it[A_ON_RIGHT] = on }
    }

    /** The consoles (platform slugs) whose A/B and X/Y are swapped from the device's layout. */
    val faceSwapped: Flow<Set<String>> = context.playerStore.data.map { it[FACE_SWAPPED].orEmpty() }

    suspend fun toggleFaceSwap(platform: String) {
        context.playerStore.edit { prefs ->
            val on = prefs[FACE_SWAPPED].orEmpty()
            prefs[FACE_SWAPPED] = if (platform in on) on - platform else on + platform
        }
    }

    /** Whether [platform]'s game is played with A on the right ([FaceButtons.aOnRight]). */
    suspend fun aOnRight(platform: String): Boolean = FaceButtons.aOnRight(aOnRight.first(), platform in faceSwapped.first())

    private companion object {
        val A_ON_RIGHT = booleanPreferencesKey("a_on_right")
        val FACE_SWAPPED = stringSetPreferencesKey("face_swapped")
        val LOW_LATENCY_OFF = stringSetPreferencesKey("low_latency_off")
        val ENABLED = booleanPreferencesKey("enabled")
        val SAMPLES = booleanPreferencesKey("yomikata_samples")
    }
}
