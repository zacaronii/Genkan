package app.genkan.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkWarmerTest {

    private fun game(
        id: Long,
        platformId: Long = 1,
        large: String? = "L$id",
        small: String? = "S$id",
        physical: String? = "P$id",
        spine: String? = null,
        logo: String? = null,
        front: String? = null,
        back: String? = null,
    ) = GameEntity(
        id = id, platformId = platformId, platformSlug = "psx", name = "Game $id", sortTitle = "game $id",
        fsName = null, fsSizeBytes = 0, coverPath = small, hasMultipleFiles = false,
        coverLargePath = large, physicalPath = physical, box2dSidePath = spine, logoPath = logo,
        box2dPath = front, box2dBackPath = back,
    )

    private val shelf = (0L until 40L).map { game(it) }

    @Test
    fun `at rest, the focused game first, then its neighbours nearest first`() {
        val games = (0L until 10L).map { game(it) }
        assertEquals(
            listOf("L5", "P5", "L4", "P4", "L6", "P6", "L3", "P3", "L7", "P7", "L2", "P2", "L8", "P8", "L1", "L9", "L0"),
            ArtworkWarmer.warmPaths(games, 5, direction = 0),
        )
    }

    @Test
    fun `at rest, every case on screen, with cut-outs only near the focus`() {
        val paths = ArtworkWarmer.warmPaths(shelf, 20, direction = 0)
        val covers = paths.filter { it.startsWith("L") }.map { it.drop(1).toInt() }.sorted()
        assertEquals((20 - ArtworkWarmer.VISIBLE..20 + ArtworkWarmer.VISIBLE).toList(), covers)
        val cutOuts = paths.filter { it.startsWith("P") }.map { it.drop(1).toInt() }.sorted()
        assertEquals((20 - ArtworkWarmer.RADIUS..20 + ArtworkWarmer.RADIUS).toList(), cutOuts)
    }

    @Test
    fun `moving, it reaches ahead in the direction of travel`() {
        val right = ArtworkWarmer.warmPaths(shelf, 10, direction = 1)
        assertEquals(listOf("L10", "L11", "L12"), right.take(3))
        assertTrue("L${10 + ArtworkWarmer.AHEAD}" in right)
        assertTrue("L${10 + ArtworkWarmer.AHEAD + 1}" !in right)
        assertTrue("L${10 - ArtworkWarmer.BEHIND}" in right)
        assertTrue("L${10 - ArtworkWarmer.BEHIND - 1}" !in right)
        // No cut-outs while moving: only the focused game at rest shows one.
        assertTrue(right.none { it.startsWith("P") })

        val left = ArtworkWarmer.warmPaths(shelf, 30, direction = -1)
        assertEquals(listOf("L30", "L29", "L28"), left.take(3))
        assertTrue("L${30 - ArtworkWarmer.AHEAD}" in left)
    }

    @Test
    fun `spines come with covers, and a logo stands in for a missing cover`() {
        val games = listOf(game(0, spine = "B0"), game(1, large = null, small = null, logo = "W1", spine = "B1"))
        assertEquals(listOf("L0", "B0", "W1", "B1"), ArtworkWarmer.warmPaths(games, 0, direction = 1))
    }

    @Test
    fun `the ends of the list clip the window`() {
        val games = (0L until 3L).map { game(it) }
        assertEquals(listOf("L0", "P0", "L1", "P1", "L2", "P2"), ArtworkWarmer.warmPaths(games, 0, direction = 0))
    }

    @Test
    fun `a missing large cover falls back to the small one, and missing art is skipped`() {
        val games = listOf(game(0, large = null, physical = null))
        assertEquals(listOf("S0"), ArtworkWarmer.warmPaths(games, 0, direction = 0))
    }

    @Test
    fun `local shelves and empty lists warm nothing`() {
        assertTrue(ArtworkWarmer.warmPaths(listOf(game(-5, platformId = -1)), 0, 0).isEmpty())
        assertTrue(ArtworkWarmer.warmPaths(emptyList(), 0, 0).isEmpty())
        assertTrue(ArtworkWarmer.warmPaths(listOf(game(0)), 4, 0).isEmpty())
    }

    /** A shelf whose games have every face of their box: front, spine and back. */
    private val boxes = (0L until 40L).map { game(it, spine = "D$it", front = "F$it", back = "B$it") }

    @Test
    fun `with the full box off, no box fronts or backs`() {
        val paths = ArtworkWarmer.warmPaths(boxes, 20, direction = 0)
        assertTrue(paths.none { it.startsWith("F") || it.startsWith("B") })
    }

    @Test
    fun `with the full box on, a front with every spine, at rest and moving`() {
        val still = ArtworkWarmer.warmPaths(boxes, 20, direction = 0, fullBox = true)
        assertEquals(still.filter { it.startsWith("D") }.map { it.drop(1) }, still.filter { it.startsWith("F") }.map { it.drop(1) })
        val moving = ArtworkWarmer.warmPaths(boxes, 20, direction = 1, fullBox = true)
        assertEquals(moving.filter { it.startsWith("D") }.map { it.drop(1) }, moving.filter { it.startsWith("F") }.map { it.drop(1) })
        assertTrue(moving.none { it.startsWith("B") })
    }

    @Test
    fun `the back only for the focused game, and only when the box shows it`() {
        assertTrue(ArtworkWarmer.warmPaths(boxes, 20, direction = 0, fullBox = true).none { it.startsWith("B") })
        val turned = ArtworkWarmer.warmPaths(boxes, 20, direction = 0, fullBox = true, showsBack = true)
        assertEquals(listOf("B20"), turned.filter { it.startsWith("B") })
    }

    @Test
    fun `no back while the focus moves, even for the focused game`() {
        for (direction in listOf(1, -1)) {
            assertTrue(ArtworkWarmer.warmPaths(boxes, 20, direction, fullBox = true, showsBack = true).none { it.startsWith("B") })
        }
    }

    @Test
    fun `no front for a game the full box can't draw`() {
        // No spine: it keeps its case, so its front isn't wanted.
        val games = (0L until 10L).map { game(it, front = "F$it") }
        assertTrue(ArtworkWarmer.warmPaths(games, 5, direction = 0, fullBox = true).none { it.startsWith("F") })
    }
}
