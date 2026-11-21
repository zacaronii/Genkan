package app.genkan.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkWarmerTest {

    private fun game(id: Long, platformId: Long = 1, large: String? = "L$id", small: String? = "S$id", physical: String? = "P$id") =
        GameEntity(
            id = id, platformId = platformId, platformSlug = "psx", name = "Game $id", sortTitle = "game $id",
            fsName = null, fsSizeBytes = 0, coverPath = small, hasMultipleFiles = false,
            coverLargePath = large, physicalPath = physical,
        )

    @Test
    fun `the focused game first, then its neighbours nearest first`() {
        val games = (0L until 10L).map { game(it) }
        assertEquals(
            listOf("L5", "P5", "L4", "P4", "L6", "P6", "L3", "P3", "L7", "P7", "L2", "P2", "L8", "P8"),
            ArtworkWarmer.neighbourPaths(games, 5, 3),
        )
    }

    @Test
    fun `the ends of the list clip the window`() {
        val games = (0L until 3L).map { game(it) }
        assertEquals(listOf("L0", "P0", "L1", "P1", "L2", "P2"), ArtworkWarmer.neighbourPaths(games, 0, 3))
    }

    @Test
    fun `a missing large cover falls back to the small one, and missing art is skipped`() {
        val games = listOf(game(0, large = null, physical = null))
        assertEquals(listOf("S0"), ArtworkWarmer.neighbourPaths(games, 0, 3))
    }

    @Test
    fun `local shelves and empty lists warm nothing`() {
        assertTrue(ArtworkWarmer.neighbourPaths(listOf(game(-5, platformId = -1)), 0, 3).isEmpty())
        assertTrue(ArtworkWarmer.neighbourPaths(emptyList(), 0, 3).isEmpty())
        assertTrue(ArtworkWarmer.neighbourPaths(listOf(game(0)), 4, 3).isEmpty())
    }
}
