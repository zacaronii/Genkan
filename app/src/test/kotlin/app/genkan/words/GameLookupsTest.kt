package app.genkan.words

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.library.LibraryDatabase
import app.genkan.ui.GameDetailsFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Lookups counted per game, for the game's details. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class GameLookupsTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.gameLookups()

    @After
    fun close() = db.close()

    @Test
    fun each_game_counts_its_own_words_and_visits() = runBlocking {
        dao.record("game:1", "食べる", "たべる", now = 1)
        dao.record("game:1", "食べる", "たべる", now = 2)
        dao.record("game:1", "見る", "みる", now = 3)
        dao.record("game:2", "食べる", "たべる", now = 4)
        assertEquals(GameLookupTotals(words = 2, lookups = 3), dao.totals("game:1").first())
        assertEquals(GameLookupTotals(words = 1, lookups = 1), dao.totals("game:2").first())
        assertEquals(GameLookupTotals(words = 0, lookups = 0), dao.totals("game:3").first())
    }

    @Test
    fun the_details_line_says_words_and_lookups() {
        assertNull(GameDetailsFormat.lookupLine(GameLookupTotals(0, 0)))
        assertEquals("Looked up 1 word", GameDetailsFormat.lookupLine(GameLookupTotals(1, 1)))
        assertEquals("Looked up 2 words (3 lookups)", GameDetailsFormat.lookupLine(GameLookupTotals(2, 3)))
        assertEquals("Looked up 1,200 words (1,530 lookups)", GameDetailsFormat.lookupLine(GameLookupTotals(1200, 1530)))
    }
}
