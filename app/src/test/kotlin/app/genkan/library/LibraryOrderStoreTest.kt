package app.genkan.library

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The shelf's sort and filter outlast Genkan: written by one store, read by a new one on the same file. */
class LibraryOrderStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A store on [name] in its own scope, closed once [use] is done (as when Genkan stops). */
    private fun <T> withStore(name: String, use: suspend (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { java.io.File(folder.root, "$name.preferences_pb") }
        return try {
            runBlocking { use(store) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the sort and filter read back after a restart`() {
        for (order in listOf(LibraryOrder(LibrarySort.MAIN_STORY, downloadedOnly = true), LibraryOrder(LibrarySort.RECENTLY_ADDED), LibraryOrder())) {
            val name = "library-${order.sort}-${order.downloadedOnly}"
            withStore(name) { store -> store.edit { LibraryViewSettings.store(it, order) } }
            assertEquals(order, withStore(name) { store -> LibraryViewSettings.orderOf(store.data.first()) })
        }
    }

    @Test
    fun `nothing stored is the default order`() {
        assertEquals(LibraryOrder(), withStore("empty") { store -> LibraryViewSettings.orderOf(store.data.first()) })
    }
}
