package app.genkan.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** The sort and the "downloaded only" filter for a console's games. */
class LibraryOrderTest {
    private fun game(id: Long, title: String, rommLastPlayed: Long? = null, mainStory: Int? = null) =
        GameEntity(id, 2, "psx", title, title.lowercase(), "$id.chd", 10, null, false, lastPlayedMillis = rommLastPlayed, hltbMainStorySec = mainStory)

    private val alpha = game(5, "Alpha", rommLastPlayed = 100, mainStory = 20 * 3600)
    private val beta = game(9, "Beta", mainStory = 5 * 3600)
    private val gamma = game(2, "Gamma", mainStory = 0)
    private val delta = game(7, "Delta")
    private val games = listOf(gamma, delta, beta, alpha)

    private fun names(order: LibraryOrder, installed: Set<Long> = emptySet(), played: Map<Long, Long> = emptyMap()) =
        order.arrange(games, installed, played).map { it.name }

    @Test
    fun `by title, as before`() {
        assertEquals(listOf("Alpha", "Beta", "Delta", "Gamma"), names(LibraryOrder()))
    }

    @Test
    fun `last played takes the later of Genkan's plays and RomM's, then title`() {
        // Delta played here at 300, Alpha in RomM at 100 and here at 50; the rest never.
        assertEquals(listOf("Delta", "Alpha", "Beta", "Gamma"), names(LibraryOrder(LibrarySort.LAST_PLAYED), played = mapOf(7L to 300L, 5L to 50L)))
    }

    @Test
    fun `recently added is the newest RomM id first`() {
        assertEquals(listOf("Beta", "Delta", "Alpha", "Gamma"), names(LibraryOrder(LibrarySort.RECENTLY_ADDED)))
    }

    @Test
    fun `main story is shortest first and games without a time last`() {
        assertEquals(listOf("Beta", "Alpha", "Delta", "Gamma"), names(LibraryOrder(LibrarySort.MAIN_STORY)))
    }

    @Test
    fun `downloaded only keeps the installed games, in the chosen order`() {
        assertEquals(listOf("Beta", "Alpha"), names(LibraryOrder(LibrarySort.MAIN_STORY, downloadedOnly = true), installed = setOf(5L, 9L)))
        assertEquals(emptyList<String>(), names(LibraryOrder(downloadedOnly = true)))
    }

    @Test
    fun `the panel steps the sort both ways and flips the filter`() {
        val start = LibraryOrder()
        assertEquals(LibrarySort.LAST_PLAYED, OrderPanel.change(start, 0, 1).sort)
        assertEquals(LibrarySort.MAIN_STORY, OrderPanel.change(start, 0, -1).sort)
        assertEquals(true, OrderPanel.change(start, 1, 1).downloadedOnly)
        assertEquals(false, OrderPanel.change(OrderPanel.change(start, 1, 1), 1, -1).downloadedOnly)
        assertEquals(LibrarySort.TITLE, LibrarySort.parse("nonsense"))
    }

    @Test
    fun `the kept focus lasts only for its own change`() {
        val keeper = FocusKeeper()
        // A new order that moves the games: the focus follows its game.
        keeper.keep(9)
        assertEquals(1, keeper.place(listOf(alpha, beta, gamma)))
        // Taken once: a later arrangement doesn't move the focus.
        assertEquals(null, keeper.place(listOf(beta, alpha)))
        // A change that gave an equal list (no new arrangement), then the panel closed: a much
        // later arrangement, from a download or a sync, leaves the focus where the player put it.
        keeper.keep(5)
        keeper.drop()
        assertEquals(null, keeper.place(listOf(gamma, alpha)))
        // A game the filter hid: the first place.
        keeper.keep(7)
        assertEquals(0, keeper.place(listOf(alpha, beta)))
    }
}
