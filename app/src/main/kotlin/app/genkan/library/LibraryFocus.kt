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

    /** Returns the new focus, or null when the key is not one the library handles. */
    fun onKey(focus: LibraryFocus, key: PadKey, platformCount: Int, gameCount: Int, columns: Int): LibraryFocus? {
        val cols = columns.coerceAtLeast(1)
        return when (key) {
            PadKey.L1 -> switchPlatform(focus, -1, platformCount)
            PadKey.R1 -> switchPlatform(focus, 1, platformCount)
            else -> when (focus.pane) {
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

    private fun switchPlatform(focus: LibraryFocus, delta: Int, platformCount: Int): LibraryFocus {
        if (platformCount == 0) return focus
        val next = (focus.platformIndex + delta).coerceIn(0, platformCount - 1)
        return if (next == focus.platformIndex) focus else focus.copy(platformIndex = next, gameIndex = 0)
    }
}
