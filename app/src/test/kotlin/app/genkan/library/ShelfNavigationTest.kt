package app.genkan.library

import app.genkan.input.PadKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfNavigationTest {

    private fun move(focus: LibraryFocus, key: PadKey, games: Int = 20, repeat: Int = 0, platforms: Int = 3) =
        LibraryNavigator.onKey(focus, key, platforms, games, columns = 4, view = LibraryView.SHELF, repeatCount = repeat)

    private val onShelf = LibraryFocus(pane = LibraryPane.GAMES, gameIndex = 5)

    @Test
    fun `left and right flip one case at a time`() {
        assertEquals(6, move(onShelf, PadKey.RIGHT)!!.gameIndex)
        assertEquals(4, move(onShelf, PadKey.LEFT)!!.gameIndex)
        // The grid's column wrap doesn't apply: left from a multiple of the columns stays on the shelf.
        assertEquals(LibraryPane.GAMES, move(onShelf.copy(gameIndex = 4), PadKey.LEFT)!!.pane)
    }

    @Test
    fun `the ends of the shelf hold`() {
        assertEquals(0, move(onShelf.copy(gameIndex = 0), PadKey.LEFT)!!.gameIndex)
        assertEquals(19, move(onShelf.copy(gameIndex = 19), PadKey.RIGHT)!!.gameIndex)
        assertEquals(19, move(onShelf.copy(gameIndex = 18), PadKey.RIGHT, repeat = 40)!!.gameIndex)
        assertEquals(0, move(onShelf.copy(gameIndex = 1), PadKey.LEFT, repeat = 40)!!.gameIndex)
    }

    @Test
    fun `holding the D-pad speeds up`() {
        assertEquals(1, ShelfStepper.step(0))
        assertEquals(1, ShelfStepper.step(ShelfStepper.FAST_AFTER - 1))
        assertEquals(2, ShelfStepper.step(ShelfStepper.FAST_AFTER))
        assertEquals(4, ShelfStepper.step(ShelfStepper.FASTER_AFTER))
        assertEquals(7, move(onShelf, PadKey.RIGHT, repeat = ShelfStepper.FAST_AFTER)!!.gameIndex)
        assertEquals(9, move(onShelf, PadKey.RIGHT, repeat = ShelfStepper.FASTER_AFTER)!!.gameIndex)
    }

    @Test
    fun `up or B goes to the console strip, down or A comes back`() {
        assertEquals(LibraryPane.PLATFORMS, move(onShelf, PadKey.UP)!!.pane)
        assertEquals(LibraryPane.PLATFORMS, move(onShelf, PadKey.B)!!.pane)
        val strip = onShelf.copy(pane = LibraryPane.PLATFORMS)
        assertEquals(onShelf, move(strip, PadKey.DOWN))
        assertEquals(onShelf, move(strip, PadKey.A))
        assertEquals(onShelf, move(onShelf, PadKey.DOWN))
    }

    @Test
    fun `left and right on the strip switch console`() {
        val strip = LibraryFocus(pane = LibraryPane.PLATFORMS, platformIndex = 1, gameIndex = 5)
        assertEquals(LibraryFocus(pane = LibraryPane.PLATFORMS, platformIndex = 2, gameIndex = 0), move(strip, PadKey.RIGHT))
        assertEquals(0, move(strip, PadKey.LEFT)!!.platformIndex)
    }

    @Test
    fun `L1 and R1 switch console from the cases too`() {
        val next = move(onShelf.copy(platformIndex = 1), PadKey.R1)!!
        assertEquals(2, next.platformIndex)
        assertEquals(0, next.gameIndex)
        assertEquals(LibraryPane.GAMES, next.pane)
        assertEquals(0, move(onShelf.copy(platformIndex = 1), PadKey.L1)!!.platformIndex)
    }

    @Test
    fun `an empty console never enters the shelf`() {
        assertEquals(LibraryPane.PLATFORMS, move(LibraryFocus(), PadKey.DOWN, games = 0)!!.pane)
        assertEquals(LibraryPane.PLATFORMS, move(onShelf, PadKey.RIGHT, games = 0)!!.pane)
    }

    @Test
    fun `keys the shelf doesn't use are left alone`() {
        assertNull(move(onShelf, PadKey.START))
        assertNull(move(LibraryFocus(), PadKey.X))
    }
}
