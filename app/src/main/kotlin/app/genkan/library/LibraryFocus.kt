package app.genkan.library

import app.genkan.input.PadKey

enum class LibraryPane { PLATFORMS, GAMES }

/** Where the gamepad is in the library. The ViewModel owns it; Compose focus is never used. */
data class LibraryFocus(
    val pane: LibraryPane = LibraryPane.PLATFORMS,
    val platformIndex: Int = 0,
    val gameIndex: Int = 0,
)

/** Pure focus moves, so navigation can be tested without a screen. */
object LibraryNavigator {

    /**
     * Returns the new focus, or null when the key is not one the library handles. [columns] is the
     * grid's row length; on the [LibraryView.SHELF] it is ignored, and [repeatCount] (the key
     * event's own, 0 for the first press) speeds up a held left or right; see [ShelfStepper].
     */
    fun onKey(
        focus: LibraryFocus,
        key: PadKey,
        platformCount: Int,
        gameCount: Int,
        columns: Int,
        view: LibraryView = LibraryView.GRID,
        repeatCount: Int = 0,
    ): LibraryFocus? {
        val cols = columns.coerceAtLeast(1)
        return when (key) {
            PadKey.L1 -> switchPlatform(focus, -1, platformCount)
            PadKey.R1 -> switchPlatform(focus, 1, platformCount)
            else -> if (view == LibraryView.SHELF) onShelfKey(focus, key, platformCount, gameCount, repeatCount) else when (focus.pane) {
                LibraryPane.PLATFORMS -> when (key) {
                    PadKey.UP -> switchPlatform(focus, -1, platformCount)
                    PadKey.DOWN -> switchPlatform(focus, 1, platformCount)
                    PadKey.RIGHT, PadKey.A ->
                        if (gameCount > 0) focus.copy(pane = LibraryPane.GAMES, gameIndex = focus.gameIndex.coerceIn(0, gameCount - 1)) else focus
                    else -> null
                }
                LibraryPane.GAMES -> if (gameCount == 0) focus.copy(pane = LibraryPane.PLATFORMS) else when (key) {
                    PadKey.LEFT ->
                        if (focus.gameIndex % cols == 0) focus.copy(pane = LibraryPane.PLATFORMS) else focus.copy(gameIndex = focus.gameIndex - 1)
                    PadKey.RIGHT -> focus.copy(gameIndex = (focus.gameIndex + 1).coerceAtMost(gameCount - 1))
                    PadKey.UP -> focus.copy(gameIndex = if (focus.gameIndex >= cols) focus.gameIndex - cols else focus.gameIndex)
                    // Down lands on the last game when the row below is shorter; no row below, no move.
                    PadKey.DOWN ->
                        if (focus.gameIndex / cols < (gameCount - 1) / cols) {
                            focus.copy(gameIndex = (focus.gameIndex + cols).coerceAtMost(gameCount - 1))
                        } else {
                            focus
                        }
                    PadKey.B -> focus.copy(pane = LibraryPane.PLATFORMS)
                    else -> null
                }
            }
        }
    }

    /**
     * The shelf: the consoles run along the top and the cases in one row below. On the console
     * strip left and right switch console and down or A goes to the cases; on the cases left and
     * right flip through them, and up or B goes back to the strip.
     */
    private fun onShelfKey(focus: LibraryFocus, key: PadKey, platformCount: Int, gameCount: Int, repeatCount: Int): LibraryFocus? =
        when (focus.pane) {
            LibraryPane.PLATFORMS -> when (key) {
                PadKey.LEFT -> switchPlatform(focus, -1, platformCount)
                PadKey.RIGHT -> switchPlatform(focus, 1, platformCount)
                PadKey.DOWN, PadKey.A ->
                    if (gameCount > 0) focus.copy(pane = LibraryPane.GAMES, gameIndex = focus.gameIndex.coerceIn(0, gameCount - 1)) else focus
                PadKey.UP -> focus
                else -> null
            }
            LibraryPane.GAMES -> if (gameCount == 0) focus.copy(pane = LibraryPane.PLATFORMS) else when (key) {
                PadKey.LEFT -> focus.copy(gameIndex = (focus.gameIndex - ShelfStepper.step(repeatCount)).coerceAtLeast(0))
                PadKey.RIGHT -> focus.copy(gameIndex = (focus.gameIndex + ShelfStepper.step(repeatCount)).coerceAtMost(gameCount - 1))
                PadKey.UP, PadKey.B -> focus.copy(pane = LibraryPane.PLATFORMS)
                PadKey.DOWN -> focus
                else -> null
            }
        }

    private fun switchPlatform(focus: LibraryFocus, delta: Int, platformCount: Int): LibraryFocus {
        if (platformCount == 0) return focus
        val next = (focus.platformIndex + delta).coerceIn(0, platformCount - 1)
        return if (next == focus.platformIndex) focus else focus.copy(platformIndex = next, gameIndex = 0)
    }
}

/**
 * How far one key event moves along the shelf. A tap moves one case; holding the D-pad sends
 * repeats about 20 times a second, and after a while each repeat skips more than one, so a long
 * shelf can be crossed without waiting.
 */
object ShelfStepper {
    /** Repeats (about 50 ms apart) held before each repeat moves two cases. */
    const val FAST_AFTER = 10
    /** Repeats held before each repeat moves four cases. */
    const val FASTER_AFTER = 30

    /** Cases to move for a key event with this [repeatCount] (0 for the first press). */
    fun step(repeatCount: Int): Int = when {
        repeatCount >= FASTER_AFTER -> 4
        repeatCount >= FAST_AFTER -> 2
        else -> 1
    }
}
