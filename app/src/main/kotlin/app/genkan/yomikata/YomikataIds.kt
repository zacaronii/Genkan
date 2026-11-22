package app.genkan.yomikata

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

private val Context.yomikataIds: DataStore<Preferences> by preferencesDataStore(name = "yomikata-ids")

/**
 * The Yomikata release id (SLPS-01001, NPJH50372…) each RomM game turned out to be, learned when
 * it ran: Genkan can't tell it from the library, only from the running game. The last one learned
 * is kept (a multi-disc game may name each disc).
 */
@Singleton
class YomikataIds @Inject constructor(@ApplicationContext private val context: Context) {

    fun idFor(romId: Long): Flow<String?> = context.yomikataIds.data.map { it[key(romId)] }

    /** Remembers that [romId] is [id]; written only when it changed. */
    suspend fun learned(romId: Long?, id: String) {
        if (romId == null || romId <= 0 || id.isBlank()) return
        val key = key(romId)
        if (context.yomikataIds.data.first()[key] == id) return
        context.yomikataIds.edit { it[key] = id }
    }

    private fun key(romId: Long) = stringPreferencesKey(romId.toString())
}

/** How this build would read a game's text, as the game's details say. */
enum class TextSource(val label: String) {
    /** A layout this build would use for the release: the game's own lines from memory. */
    LAYOUT("Text: layout"),
    /** The game's own character table (memory checked against OCR), with no layout to use. */
    TABLE("Text: table"),
    /** A PSP hook in PPSSPP. */
    HOOK("Text: hook");

    companion object {
        /**
         * The source for a game known as [id] (null before it has run), with [entryFor] the
         * cached catalog's entry and [tablesHere] whether an entry's table files are on the
         * device. Null, no chip, when nothing is known: then OCR reads it.
         */
        fun of(id: String?, entryFor: (String) -> CatalogEntry?, tablesHere: (CatalogEntry) -> Boolean): TextSource? {
            val entry = id?.let(entryFor) ?: return null
            return when {
                // The layout the built-in player would pick for this release (and, with a reader
                // that passes over layouts it doesn't understand, only one it does).
                Layouts.pick(entry, id) != null -> LAYOUT
                entry.encoding == "table" && entry.tables.isNotEmpty() && tablesHere(entry) -> TABLE
                entry.platform == "psp" && entry.hooks.isNotEmpty() -> HOOK
                else -> null
            }
        }
    }
}
