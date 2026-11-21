package app.genkan.words

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

private val Context.wordStore: DataStore<Preferences> by preferencesDataStore(name = "words")

/** Settings for word marks and lookup counts. */
@Singleton
class WordSettings @Inject constructor(@ApplicationContext private val context: Context) {

    /** Record lookups and show "Looked up N times" on the first result (Hachidori's default: on). */
    val counts: Flow<Boolean> = context.wordStore.data.map { it[COUNTS] ?: true }

    suspend fun countsOn(): Boolean = counts.first()

    suspend fun setCounts(on: Boolean) {
        context.wordStore.edit { it[COUNTS] = on }
    }

    private companion object {
        val COUNTS = booleanPreferencesKey("lookup_counts")
    }
}
