package app.genkan.manual

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.library.GameEntity
import app.genkan.library.LibraryDatabase
import app.genkan.reader.NowPlaying
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** The playing game's manual over the reader: when it opens, what a fetch does, and the page kept. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PlayManualTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
    private val nowPlaying = NowPlaying()
    private val source = FakeSource()

    /** A manual source whose fetches wait for [answer] (a file, or a failure). */
    private inner class FakeSource : ManualSource {
        var fetches = 0
        var answer = CompletableDeferred<Result<File>>()

        override fun has(game: GameEntity) = game.manualPath != null
        override fun cached(game: GameEntity): File? = null
        override suspend fun file(game: GameEntity): File {
            fetches++
            return answer.await().getOrThrow()
        }
    }

    private fun game(id: Long, manual: Boolean = true) =
        GameEntity(id, 2, "psx", "Game $id", "game $id", "game$id.chd", 10, null, false, manualPath = if (manual) "/manuals/$id.pdf" else null)

    private fun pdf(name: String) = File(context.cacheDir, name).apply { writeText("pdf") }

    /** The PlayManuals' scope: cancelled before the database closes, so nothing of theirs outlives the test. */
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun close() {
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    /** A PlayManual with [game] playing, once it has looked the game up. */
    private fun playing(game: GameEntity): PlayManual = runBlocking {
        db.library().upsertGames(listOf(game))
        val manual = PlayManual(nowPlaying, db.library(), source, scope)
        nowPlaying.started(game.id, game.name, "psx")
        if (game.manualPath != null) withTimeout(5_000) { manual.game.first { it?.id == game.id } }
        manual
    }

    @Test
    fun `show does nothing for a game without a manual`() {
        val manual = playing(game(1, manual = false))
        manual.show()
        assertEquals(0, source.fetches)
        assertNull(manual.open.value)
    }

    @Test
    fun `show twice fetches once`() {
        val manual = playing(game(1))
        manual.show()
        manual.show()
        assertEquals(1, source.fetches)
        assertEquals("Fetching the manual…", manual.notice.value)
        val file = pdf("1-a.pdf")
        source.answer.complete(Result.success(file))
        assertEquals(file, manual.open.value)
        assertNull(manual.notice.value)
    }

    @Test
    fun `a failed fetch says why and opens nothing`() {
        val manual = playing(game(1))
        manual.show()
        source.answer.complete(Result.failure(IOException("the server answered 500")))
        assertNull(manual.open.value)
        assertEquals("Couldn't fetch the manual: the server answered 500", manual.notice.value)
    }

    @Test
    fun `hide during a fetch cancels it`() {
        val manual = playing(game(1))
        manual.show()
        manual.hide()
        source.answer.complete(Result.success(pdf("1-a.pdf")))
        assertNull(manual.open.value)
        assertNull(manual.notice.value)
    }

    @Test
    fun `a manual that wouldn't open is deleted, and the reader says why`() {
        val manual = playing(game(1))
        val file = pdf("1-a.pdf")
        manual.show()
        source.answer.complete(Result.success(file))
        manual.closed(file, "Couldn't open the manual: broken")
        assertNull(manual.open.value)
        assertEquals("Couldn't open the manual: broken", manual.notice.value)
        runBlocking { withTimeout(5_000) { while (file.exists()) kotlinx.coroutines.delay(10) } }
        assertFalse(file.exists())
    }

    @Test
    fun `the page is kept for a file, and another game closes the manual and forgets it`() {
        val manual = playing(game(1))
        val file = pdf("1-a.pdf")
        manual.show()
        source.answer.complete(Result.success(file))
        manual.keepPage(file, 4)
        assertEquals(4, manual.pageOf(file))
        runBlocking { db.library().upsertGames(listOf(game(2))) }
        nowPlaying.started(2, "Game 2", "psx")
        assertNull(manual.open.value)
        assertEquals(0, manual.pageOf(file))
    }
}
