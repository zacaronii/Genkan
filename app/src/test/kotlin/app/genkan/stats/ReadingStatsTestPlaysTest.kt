package app.genkan.stats

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.home.LauncherPresence
import app.genkan.library.LibraryDatabase
import app.genkan.reader.NowPlaying
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A test play's reading is never counted, so nothing of it can reach the reading log. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ReadingStatsTestPlaysTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.reading()
    private val playing = NowPlaying()
    private val stats = ReadingStats(dao, playing, LauncherPresence())

    @After
    fun close() = db.close()

    /** Waits for the stats' own thread to write what it has. */
    private fun settle(): List<ReadingSessionTotals> = runBlocking {
        stats.flush()
        var sessions = emptyList<ReadingSessionTotals>()
        repeat(50) {
            Thread.sleep(20)
            sessions = dao.recentTotals(100)
        }
        sessions
    }

    @Test
    fun `lines of a test play are counted nowhere, an ordinary play's are`() {
        playing.started(7, "Made-up Quest", "snes", test = true)
        stats.onLine("今日は学校に行きました", "今日は学校に行きました")
        assertTrue(settle().isEmpty())

        playing.started(8, "Pretend Tale", "snes")
        stats.onLine("今日は学校に行きました", "今日は学校に行きました")
        assertEquals(listOf(8L), settle().map { it.rommRomId })
    }

    @Test
    fun `a device round taking over the owner's game adds nothing to the owner's session`() {
        playing.started(8, "Pretend Tale", "snes")
        stats.onLine("今日は学校に行きました", "今日は学校に行きました")
        val before = settle().single()
        playing.markTest()
        stats.onLine("明日は図書館で本を読みます", "明日は図書館で本を読みます")
        assertEquals(before.characters, settle().single().characters)
    }
}
