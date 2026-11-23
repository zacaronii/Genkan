package app.genkan.ui

import app.genkan.library.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxTurnTest {
    private fun game(id: Long, back: Boolean = true, spine: Boolean = true) =
        GameEntity(id, 2, "psx", "Game $id", "game $id", "game$id.chd", 10, null, false).copy(
            box2dPath = "/f/$id.png",
            box2dSidePath = if (spine) "/s/$id.png" else null,
            box2dBackPath = if (back) "/b/$id.png" else null,
        )

    @Test
    fun `R3 turns the focused box to its back, and again to its front`() {
        val game = game(1)
        val turned = BoxTurn.onR3(null, game, fullBox = true)
        assertEquals(1L, turned)
        assertTrue(BoxTurn.isTurned(turned, game))
        assertNull(BoxTurn.onR3(turned, game, fullBox = true))
    }

    @Test
    fun `a game that can't turn isn't turned, and R3 changes nothing`() {
        for ((game, fullBox) in listOf(game(1, back = false) to true, game(1, spine = false) to true, game(1) to false)) {
            assertFalse(BoxTurn.canTurn(game, fullBox))
            assertNull(BoxTurn.onR3(null, game, fullBox))
        }
        assertNull(BoxTurn.onR3(null, null, fullBox = true))
    }

    @Test
    fun `a move of the focus turns it back to its front`() {
        val turned = BoxTurn.onR3(null, game(1), fullBox = true)
        assertTrue(BoxTurn.isTurned(turned, game(1)))
        assertNull(BoxTurn.onFocusMoved())
    }

    @Test
    fun `a game that comes under an unmoved focus faces front, and R3 turns it`() {
        val turned = BoxTurn.onR3(null, game(1), fullBox = true)
        // The list changed (a game taken off the Favourites shelf, a sync): game 2 is now focused.
        assertFalse(BoxTurn.isTurned(turned, game(2)))
        // With no back, it has no hint and R3 does nothing; it still shows its front.
        assertFalse(BoxTurn.isTurned(BoxTurn.onR3(turned, game(2, back = false), fullBox = true), game(2, back = false)))
        // With one, R3 turns it at the first press.
        assertTrue(BoxTurn.isTurned(BoxTurn.onR3(turned, game(2), fullBox = true), game(2)))
    }
}
