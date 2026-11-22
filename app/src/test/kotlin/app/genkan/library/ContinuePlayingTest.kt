package app.genkan.library

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.play.PlaySessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The "Continue playing" shelf: which games it holds and where the focus goes as it changes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ContinuePlayingTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
        .allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private fun game(id: Long, platformId: Long = 2) =
        GameEntity(id, platformId, "psx", "Game $id", "game $id", "game$id.chd", 10, null, false)

    private fun played(romId: Long, startedAt: Long, durationMs: Long? = 60_000, ended: Boolean = true) = runBlocking {
        db.playSessions().insert(
            PlaySessionEntity(romId = romId, emulatorPackage = "builtin", startedAt = startedAt,
                endedAt = if (ended) startedAt + (durationMs ?: 0) else null, durationMs = durationMs),
        )
    }

    private fun shelf() = runBlocking { db.playSessions().recentlyPlayed(ContinuePlaying.MIN_PLAY_MS, ContinuePlaying.MAX_GAMES).first().map { it.id } }

    @Test
    fun the_most_recently_played_come_first_once_each() = runBlocking {
        db.library().upsertGames((1L..3L).map { game(it) })
        played(1, startedAt = 100)
        played(2, startedAt = 300)
        played(1, startedAt = 200)
        assertEquals(listOf(2L, 1L), shelf())
    }

    @Test
    fun a_short_or_running_play_does_not_count() = runBlocking {
        db.library().upsertGames((1L..3L).map { game(it) })
        played(1, startedAt = 100)
        played(2, startedAt = 200, durationMs = ContinuePlaying.MIN_PLAY_MS - 1)
        played(3, startedAt = 300, durationMs = null, ended = false)
        // A mis-tap on game 1 after a real play leaves it where its real play put it.
        played(1, startedAt = 400, durationMs = 2_000)
        played(2, startedAt = 50)
        assertEquals(listOf(1L, 2L), shelf())
    }

    @Test
    fun at_most_ten_games() = runBlocking {
        db.library().upsertGames((1L..12L).map { game(it) })
        (1L..12L).forEach { played(it, startedAt = it * 100) }
        assertEquals((12L downTo 3L).toList(), shelf())
    }

    @Test
    fun a_game_deleted_from_the_device_stays_and_one_gone_from_the_library_leaves() = runBlocking {
        db.library().upsertGames((1L..2L).map { game(it) })
        played(1, startedAt = 100)
        played(2, startedAt = 200)
        // Game 1 was deleted from the device, so it has no download row: still on the shelf, where
        // its card shows "not downloaded" and A downloads it again.
        // Game 2 left RomM's library.
        db.library().deleteGamesExcept(platformId = 2, keep = listOf(1L))
        assertEquals(listOf(1L), shelf())
    }

    @Test
    fun a_game_on_the_shelf_stays_on_its_console() = runBlocking {
        db.library().upsertGames((1L..2L).map { game(it) })
        played(2, startedAt = 100)
        assertEquals(listOf(2L), shelf())
        assertEquals(listOf(1L, 2L), db.library().games(2).first().map { it.id })
    }

    private fun platform(id: Long) = PlatformEntity(id, "p$id", null, "P$id", 1, null)
    private val psx = platform(2)
    private val snes = platform(3)
    private val shelfPlatform = ContinuePlaying.platform(1)

    @Test
    fun the_library_opens_on_the_shelf_until_the_player_moves() {
        val start = LibraryFocus()
        val landed = ContinuePlaying.refocus(start, listOf(psx, snes), listOf(shelfPlatform, psx, snes), moved = false)
        assertEquals(LibraryFocus(pane = LibraryPane.GAMES, platformIndex = 0, gameIndex = 0), landed)
        // Without the shelf (nothing played yet) the strip's own start is kept.
        assertEquals(start, ContinuePlaying.refocus(start, emptyList(), listOf(psx, snes), moved = false))
    }

    @Test
    fun once_moved_the_focus_stays_on_its_console_as_the_strip_shifts() {
        val onSnes = LibraryFocus(pane = LibraryPane.GAMES, platformIndex = 1, gameIndex = 4)
        // The shelf appears in front: SNES moves from 1 to 2, its game kept.
        assertEquals(onSnes.copy(platformIndex = 2), ContinuePlaying.refocus(onSnes, listOf(psx, snes), listOf(shelfPlatform, psx, snes), moved = true))
        // A console that is gone sends the focus back to the strip.
        assertEquals(
            LibraryFocus(pane = LibraryPane.PLATFORMS, platformIndex = 0),
            ContinuePlaying.refocus(onSnes, listOf(psx, snes), listOf(psx), moved = true),
        )
    }

    @Test
    fun a_game_on_the_shelf_is_named_with_its_own_console() {
        val strip = listOf(shelfPlatform, psx, snes)
        assertEquals("P2", ContinuePlaying.consoleOf(game(1, platformId = 2), strip, shelfPlatform))
        assertEquals("P3", ContinuePlaying.consoleOf(game(1, platformId = 3), strip, snes))
    }

    @Test
    fun after_a_play_the_focus_follows_its_game_to_the_front() {
        val before = listOf(game(1), game(2), game(3))
        assertEquals(0, ContinuePlaying.keepGame(2, before, listOf(game(3), game(1), game(2))))
        // A game that left: the nearest place.
        assertEquals(1, ContinuePlaying.keepGame(2, before, listOf(game(1), game(2))))
        assertEquals(0, ContinuePlaying.keepGame(1, before, emptyList()))
    }
}
