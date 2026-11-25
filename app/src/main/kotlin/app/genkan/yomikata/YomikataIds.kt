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
    /** A Yomikata script reads the game: its own lines from memory. */
    SCRIPT("Text: script"),
    /** The game's own character table (memory checked against OCR), with no script. */
    TABLE("Text: table"),
    /** The same, from a table the catalog marks as partial: some characters may not read. */
    TABLE_PARTIAL("Text: table (partial)"),
    /** A PSP hook in PPSSPP (the emulator app; the built-in player doesn't hook). */
    HOOK("Text: hook");

    companion object {
        /**
         * The source for a game known as [id] (null before it has run), with [entryFor] the
         * cached catalog's entry, [tablesHere] whether an entry's table files are on the device,
         * and [hasScript] whether the cached index lists a script for the id. [builtIn] is whether
         * the game starts in the built-in player (the setting is on and Genkan has a core for its
         * console): scripts and tables are read there, scripts in ARMSX2 too; in another emulator
         * app only PPSSPP's hooks are. Null, no chip, when nothing applies: then OCR reads it.
         */
        fun of(
            id: String?,
            entryFor: (String) -> CatalogEntry?,
            tablesHere: (CatalogEntry) -> Boolean,
            builtIn: Boolean,
            hasScript: (String) -> Boolean = { false },
        ): TextSource? {
            val entry = id?.let(entryFor) ?: return null
            if (hasScript(id) && (builtIn || entry.platform == PS2)) return SCRIPT
            if (!builtIn) return HOOK.takeIf { entry.platform == "psp" && entry.hooks.isNotEmpty() }
            return when {
                entry.encoding == "table" && entry.tables.isNotEmpty() && tablesHere(entry) ->
                    if (entry.status == "partial") TABLE_PARTIAL else TABLE
                else -> null
            }
        }

        /** ARMSX2 runs a PS2 game's script, as the built-in player does. */
        private const val PS2 = "ps2"
    }
}
