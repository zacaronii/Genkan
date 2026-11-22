package app.genkan.library

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The "Favourites" shelf: which games it holds, in what order, and what a sync does to it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class FavouritesTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.favourites()

    @After
    fun close() = db.close()

    private fun game(id: Long, title: String, platformId: Long = 2) =
        GameEntity(id, platformId, "psx", title, title.lowercase(), "$id.chd", 10, null, false)

    private fun shelf() = runBlocking { dao.games().first().map { it.id } }

    @Test
    fun favourites_are_shown_by_title_once_each() = runBlocking {
        db.library().upsertGames(listOf(game(1, "Zeta"), game(2, "Alpha"), game(3, "Mu")))
        dao.add(FavouriteEntity(1, addedAt = 100))
        dao.add(FavouriteEntity(2, addedAt = 200))
        // Marking a game twice keeps the first mark.
        dao.add(FavouriteEntity(1, addedAt = 300))
        assertEquals(listOf(2L, 1L), shelf())
        assertEquals(setOf(1L, 2L), dao.ids().first().toSet())
    }

    @Test
    fun unmarking_takes_a_game_off_the_shelf() = runBlocking {
        db.library().upsertGames(listOf(game(1, "Alpha")))
        dao.add(FavouriteEntity(1, addedAt = 100))
        dao.remove(1)
        assertEquals(emptyList<Long>(), shelf())
    }

    @Test
    fun two_quick_toggles_mark_and_unmark() = runBlocking {
        db.library().upsertGames(listOf(game(1, "Alpha")))
        assertTrue(dao.toggle(1, now = 100))
        assertFalse(dao.toggle(1, now = 101))
        assertEquals(emptyList<Long>(), shelf())
        // Started together, as from two presses the screen hadn't caught up with: each decides
        // from what is stored, so an even number of toggles always leaves the game unmarked.
        val results = (1..4).map { async(Dispatchers.IO) { dao.toggle(1, now = 200L + it) } }.awaitAll()
        assertEquals(2, results.count { it })
        assertEquals(emptyList<Long>(), shelf())
    }

    @Test
    fun a_game_gone_from_the_library_leaves_the_shelf_and_comes_back_with_its_mark() = runBlocking {
        db.library().upsertGames(listOf(game(1, "Alpha"), game(2, "Beta")))
        dao.add(FavouriteEntity(1, addedAt = 100))
        dao.add(FavouriteEntity(2, addedAt = 100))
        // A sync drops game 2: off the shelf, though still marked.
        db.library().deleteGamesExcept(platformId = 2, keep = listOf(1L))
        assertEquals(listOf(1L), shelf())
        // The next sync brings it back, and it's a favourite again.
        db.library().upsertGames(listOf(game(2, "Beta")))
        assertEquals(listOf(1L, 2L), shelf())
    }

    @Test
    fun a_sync_rewriting_a_game_keeps_its_mark_and_its_console_shelf() = runBlocking {
        db.library().upsertGames(listOf(game(1, "Alpha")))
        dao.add(FavouriteEntity(1, addedAt = 100))
        db.library().upsertGames(listOf(game(1, "Alpha (Rev 1)")))
        assertEquals(listOf(1L), shelf())
        assertEquals(listOf(1L), db.library().games(2).first().map { it.id })
    }

    @Test
    fun only_romm_games_can_be_marked() {
        assertTrue(Favourites.canMark(game(1, "Alpha")))
        assertFalse(Favourites.canMark(game(-5, "An app", platformId = -2)))
    }
}
