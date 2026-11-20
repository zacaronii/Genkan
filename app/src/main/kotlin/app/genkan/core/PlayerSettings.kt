package app.genkan.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
    }
}
