package app.genkan.library

import app.genkan.play.GameLastPlayed

/**
 * The "Continue playing" shelf: the RomM and PC games played most recently, newest first, as the
 * first entry on the console strip. It is made from the play sessions Genkan records, so it holds
 * only games Genkan started (apps leave no sessions), and it is hidden until one has been played.
 * Each game stays on its own shelf too.
 *
 * A game played and then deleted from the device stays here with the "not downloaded" state, like
 * on its console's shelf: A downloads it again. One gone from RomM's library leaves the shelf with
 * its game row.
 */
object ContinuePlaying {
    /** Its id on the strip; local shelves are negative too (PC -1, apps -2). */
    const val ID = -3L
    const val SLUG = "genkan-continue"
    const val NAME = "Continue playing"
    const val MAX_GAMES = 10

    /**
     * A play shorter than this doesn't count: a mis-tap, or an emulator that closed straight away,
     * never moves a game up the shelf.
     */
    const val MIN_PLAY_MS = 10_000L

    fun platform(games: Int) = PlatformEntity(ID, SLUG, null, NAME, games, null)

    /**
     * The shelf's games: [plays] (every game's latest play, newest first) as RomM's [romm] and the
     * PC shelf's [pc] games, skipping any no longer there, at most [MAX_GAMES].
     */
    fun merge(plays: List<GameLastPlayed>, romm: List<GameEntity>, pc: List<GameEntity>): List<GameEntity> {
        val byId = (romm + pc).associateBy { it.id }
        return plays.mapNotNull { byId[it.romId] }.take(MAX_GAMES)
    }

    /** The console [game] belongs to, named as on the strip; on this shelf, not the shelf's name. */
    fun consoleOf(game: GameEntity, platforms: List<PlatformEntity>, shelf: PlatformEntity?): String =
        platforms.firstOrNull { it.id == game.platformId }?.name ?: shelf?.name.orEmpty()

    /**
     * The focus once the strip changed from [before] to [after] (this shelf appearing, or a local
     * shelf loading, shifts the others). Until the player has [moved], the library opens on this
     * shelf's first game, or without it at the strip's first entry. After that the focus stays on the same console, wherever it now stands;
     * on one that is gone, it goes back to the strip.
     */
    fun refocus(focus: LibraryFocus, before: List<PlatformEntity>, after: List<PlatformEntity>, moved: Boolean): LibraryFocus {
        // The shelves arrive one by one (a local one, "Continue playing", "Favourites"), so until
        // the player moves the library always opens at the strip's start, never on whichever
        // console happened to be first a moment ago.
        if (!moved) {
            return if (after.firstOrNull()?.id == ID) LibraryFocus(pane = LibraryPane.GAMES, platformIndex = 0, gameIndex = 0) else LibraryFocus()
        }
        val id = before.getOrNull(focus.platformIndex)?.id
        val index = after.indexOfFirst { it.id == id }
        if (index == focus.platformIndex) return focus
        if (index >= 0) return focus.copy(platformIndex = index)
        return LibraryFocus(pane = LibraryPane.PLATFORMS, platformIndex = focus.platformIndex.coerceIn(0, (after.size - 1).coerceAtLeast(0)))
    }

    /**
     * Where the focus goes on this shelf when its order changes: to the same game, which after a
     * play is the first; to the nearest place when that game has left the shelf.
     */
    fun keepGame(index: Int, before: List<GameEntity>, after: List<GameEntity>): Int {
        if (after.isEmpty()) return 0
        val id = before.getOrNull(index)?.id
        val now = after.indexOfFirst { it.id == id }
        return if (now >= 0) now else index.coerceIn(0, after.size - 1)
    }
}
