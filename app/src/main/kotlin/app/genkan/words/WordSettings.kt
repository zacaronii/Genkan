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

    /** Hachidori's brief definitions beside each headword, and how many (1 to 6; Hachidori's default: off, 3). */
    val summary: Flow<Boolean> = context.wordStore.data.map { it[SUMMARY] ?: false }
    val summaryCount: Flow<Int> = context.wordStore.data.map { it[SUMMARY_COUNT] ?: 3 }

    suspend fun setSummary(on: Boolean) {
        context.wordStore.edit { it[SUMMARY] = on }
    }

    suspend fun setSummaryCount(count: Int) {
        context.wordStore.edit { it[SUMMARY_COUNT] = count.coerceIn(1, 6) }
    }

    private companion object {
        val COUNTS = booleanPreferencesKey("lookup_counts")
        val SUMMARY = booleanPreferencesKey("summary")
        val SUMMARY_COUNT = androidx.datastore.preferences.core.intPreferencesKey("summary_count")
    }
}
