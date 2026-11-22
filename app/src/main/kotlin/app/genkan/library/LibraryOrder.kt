package app.genkan.library

/** How a console's games are ordered on the shelf and in the grid. */
enum class LibrarySort(val label: String) {
    TITLE("Title"),
    /** Most recently played first: Genkan's own plays or RomM's last-played time, whichever is later. */
    LAST_PLAYED("Last played"),
    /** Newest in RomM first. RomM numbers games as they are added, so its id is the order they came in. */
    RECENTLY_ADDED("Recently added"),
    /** Shortest HowLongToBeat main story first; games without a time go last. */
    MAIN_STORY("Main story length");

    fun next(step: Int): LibrarySort = entries[Math.floorMod(ordinal + step, entries.size)]

    companion object {
        val DEFAULT = TITLE

        fun parse(name: String?): LibrarySort = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** The sort and the one filter, remembered together. */
data class LibraryOrder(val sort: LibrarySort = LibrarySort.DEFAULT, val downloadedOnly: Boolean = false) {

    /**
     * A RomM console's [games] in this order: with [downloadedOnly], only those in [installed];
     * [lastPlayed] is Genkan's own latest play per game. Ties keep title order.
     */
    fun arrange(games: List<GameEntity>, installed: Set<Long>, lastPlayed: Map<Long, Long>): List<GameEntity> {
        val shown = if (downloadedOnly) games.filter { it.id in installed } else games
        val byTitle = compareBy<GameEntity> { it.sortTitle }
        return when (sort) {
            LibrarySort.TITLE -> shown.sortedWith(byTitle)
            LibrarySort.LAST_PLAYED -> shown.sortedWith(
                compareByDescending<GameEntity> { maxOf(lastPlayed[it.id] ?: 0L, it.lastPlayedMillis ?: 0L) }.then(byTitle),
            )
            LibrarySort.RECENTLY_ADDED -> shown.sortedWith(compareByDescending<GameEntity> { it.id }.then(byTitle))
            LibrarySort.MAIN_STORY -> shown.sortedWith(
                compareBy<GameEntity> { it.hltbMainStorySec?.takeIf { s -> s > 0 } == null }
                    .thenBy { it.hltbMainStorySec ?: 0 }.then(byTitle),
            )
        }
    }
}

/** Where the pad is in the "Sort & filter" panel: its rows, and what a key does there. */
object OrderPanel {
    const val ROWS = 2

    /**
     * The order after a change on [row] (0 is the sort, 1 the filter): [step] (+1 right or A, -1
     * left) moves the sort through its choices; on the filter any change flips it.
     */
    fun change(order: LibraryOrder, row: Int, step: Int): LibraryOrder = when (row) {
        0 -> order.copy(sort = order.sort.next(step))
        else -> order.copy(downloadedOnly = !order.downloadedOnly)
    }
}

/**
 * The game the focus stays on while a new order rearranges a console's shelf. It lasts only as
 * long as its change: the next arrangement takes it, and closing the panel or moving the focus
 * drops it, so an arrangement that never changed can't pull the focus back later.
 */
class FocusKeeper {
    private var id: Long? = null

    /** Keep the focus on [game] through the next arrangement. */
    fun keep(game: Long?) {
        id = game
    }

    fun drop() {
        id = null
    }

    /** Where the focus goes in a new arrangement [games]: the kept game's place, the first when it's hidden, or null when nothing is kept. */
    fun place(games: List<GameEntity>): Int? {
        val kept = id ?: return null
        id = null
        return games.indexOfFirst { it.id == kept }.takeIf { it >= 0 } ?: 0
    }
}
