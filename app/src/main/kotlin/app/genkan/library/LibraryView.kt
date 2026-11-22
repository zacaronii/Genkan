package app.genkan.library

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** How the library shows a console's games on the top screen. */
enum class LibraryView {
    /** A row of game cases, face-out, flipped through one at a time. */
    SHELF,
    /** The cover grid, for browsing a long list quickly. */
    GRID;

    /** The other view, for the toggle button. */
    fun toggled(): LibraryView = if (this == SHELF) GRID else SHELF

    companion object {
        /** The view a new install starts in. */
        val DEFAULT = SHELF

        /** The view stored as [name], or [DEFAULT] when nothing (or something unknown) is stored. */
        fun parse(name: String?): LibraryView = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

private val Context.libraryStore: DataStore<Preferences> by preferencesDataStore(name = "library")

/** Remembers which [LibraryView] the library was last left in, and the library's other choices. */
@Singleton
class LibraryViewSettings @Inject constructor(@ApplicationContext private val context: Context) {

    val view: Flow<LibraryView> = context.libraryStore.data.map { LibraryView.parse(it[VIEW]) }

    suspend fun setView(view: LibraryView) {
        context.libraryStore.edit { it[VIEW] = view.name }
    }

    /** Whether starting a game plays the insert animation (on unless switched off). */
    val insertAnimation: Flow<Boolean> = context.libraryStore.data.map { it[INSERT_ANIMATION] != false }

    suspend fun setInsertAnimation(on: Boolean) {
        context.libraryStore.edit { it[INSERT_ANIMATION] = on }
    }

    /** The sort and filter for consoles' games (not "Continue playing" or "Favourites"). */
    val order: Flow<LibraryOrder> = context.libraryStore.data.map(::orderOf)

    suspend fun setOrder(order: LibraryOrder) {
        context.libraryStore.edit { store(it, order) }
    }

    internal companion object {
        /** The sort and filter as [prefs] hold them. */
        fun orderOf(prefs: Preferences) = LibraryOrder(LibrarySort.parse(prefs[SORT]), prefs[DOWNLOADED_ONLY] == true)

        /** Writes [order] into [prefs], for [orderOf] to read back. */
        fun store(prefs: MutablePreferences, order: LibraryOrder) {
            prefs[SORT] = order.sort.name
            prefs[DOWNLOADED_ONLY] = order.downloadedOnly
        }

        private val SORT = stringPreferencesKey("sort")
        private val DOWNLOADED_ONLY = booleanPreferencesKey("downloaded_only")
        private val VIEW = stringPreferencesKey("view")
        private val INSERT_ANIMATION = booleanPreferencesKey("insert_animation")
    }
}
