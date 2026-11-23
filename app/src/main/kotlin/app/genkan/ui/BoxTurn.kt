package app.genkan.ui

import app.genkan.library.GameEntity
import app.genkan.library.hasBoxFaces

/**
 * Which game's full box is turned to its back: held by the game's id, so a box shows turned only
 * for the game it was turned for. When the list changes under an unmoved focus (a game taken off
 * the Favourites shelf, a sync), the game now there faces front.
 */
object BoxTurn {
    /** Whether R3 can turn [game]'s box: the full box is on, it can be drawn as one, and it has a back. */
    fun canTurn(game: GameEntity, fullBox: Boolean): Boolean = fullBox && game.hasBoxFaces() && game.box2dBackPath != null

    /** The turned game after R3 on the focused [game]: turned if it wasn't, back to front if it was; nothing else changes it. */
    fun onR3(turnedId: Long?, game: GameEntity?, fullBox: Boolean): Long? = when {
        game == null || !canTurn(game, fullBox) -> turnedId
        turnedId == game.id -> null
        else -> game.id
    }

    /** A move of the focus (or a change of shelf or pane) turns any box back to its front. */
    fun onFocusMoved(): Long? = null

    /** Whether [game], the focused one, shows its back. */
    fun isTurned(turnedId: Long?, game: GameEntity?): Boolean = game != null && turnedId == game.id
}
