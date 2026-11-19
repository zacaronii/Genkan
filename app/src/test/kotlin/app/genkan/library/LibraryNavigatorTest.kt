package app.genkan.library

import app.genkan.input.PadKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryNavigatorTest {

    private fun move(focus: LibraryFocus, key: PadKey, games: Int = 10, columns: Int = 4, platforms: Int = 3) =
        LibraryNavigator.onKey(focus, key, platforms, games, columns)

    private val inGames = LibraryFocus(pane = LibraryPane.GAMES)

    @Test
    fun `up and down on the rail switch platform and reset the game`() {
        val next = move(LibraryFocus(gameIndex = 5), PadKey.DOWN)!!
        assertEquals(1, next.platformIndex)
        assertEquals(0, next.gameIndex)
        assertEquals(0, move(LibraryFocus(), PadKey.UP)!!.platformIndex)
        assertEquals(2, move(LibraryFocus(platformIndex = 2), PadKey.DOWN)!!.platformIndex)
    }

    @Test
    fun `right or A enters the grid, left from the first column leaves it`() {
        assertEquals(LibraryPane.GAMES, move(LibraryFocus(), PadKey.RIGHT)!!.pane)
        assertEquals(LibraryPane.GAMES, move(LibraryFocus(), PadKey.A)!!.pane)
        assertEquals(LibraryPane.PLATFORMS, move(inGames.copy(gameIndex = 4), PadKey.LEFT)!!.pane)
        assertEquals(4, move(inGames.copy(gameIndex = 5), PadKey.LEFT)!!.gameIndex)
    }

    @Test
    fun `an empty platform never enters the grid`() {
        assertEquals(LibraryPane.PLATFORMS, move(LibraryFocus(), PadKey.RIGHT, games = 0)!!.pane)
        assertEquals(LibraryPane.PLATFORMS, move(inGames, PadKey.DOWN, games = 0)!!.pane)
    }

    @Test
    fun `up and down move whole rows within the grid`() {
        assertEquals(5, move(inGames.copy(gameIndex = 1), PadKey.DOWN)!!.gameIndex)
        assertEquals(1, move(inGames.copy(gameIndex = 5), PadKey.UP)!!.gameIndex)
        assertEquals(1, move(inGames.copy(gameIndex = 1), PadKey.UP)!!.gameIndex)
    }

    @Test
    fun `down onto a short last row lands on the last game, and stops at the bottom`() {
        // 10 games in 4 columns: rows 0-3, 4-7, 8-9.
        assertEquals(9, move(inGames.copy(gameIndex = 7), PadKey.DOWN)!!.gameIndex)
        assertEquals(9, move(inGames.copy(gameIndex = 9), PadKey.DOWN)!!.gameIndex)
        assertEquals(9, move(inGames.copy(gameIndex = 9), PadKey.RIGHT)!!.gameIndex)
    }

    @Test
    fun `shoulder buttons switch platform from anywhere, B returns to the rail`() {
        val next = move(inGames.copy(gameIndex = 6), PadKey.R1)!!
        assertEquals(1, next.platformIndex)
        assertEquals(0, next.gameIndex)
        assertEquals(LibraryPane.PLATFORMS, move(inGames, PadKey.B)!!.pane)
    }

    @Test
    fun `unhandled keys fall through`() {
        assertNull(move(LibraryFocus(), PadKey.X))
    }
}
