package app.genkan.art

import app.genkan.library.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtKeysTest {
    @Test
    fun `the key is the path without its ts or the server's address`() {
        assertEquals("romm-art:assets/romm/resources/roms/1/cover.png", RomMArt.keyOf("/assets/romm/resources/roms/1/cover.png?ts=2026-11-20 01:02:03"))
        assertEquals(RomMArt.keyOf("/a/b.png?ts=1"), RomMArt.keyOf("/a/b.png?ts=2"))
        assertEquals("romm-art:a/b.png?size=big", RomMArt.keyOf("/a/b.png?size=big&ts=3"))
        assertNull(RomMArt.of("https://cdn.example.com/steam/1.jpg"))
        assertNull(RomMArt.of(" "))
    }

    @Test
    fun `the disk limit is a gigabyte, at most 5 percent of the volume and never under 250 MB`() {
        val mb = 1L shl 20
        assertEquals(1024 * mb, ArtCache.limitFor(512L * 1024 * mb))
        assertEquals(10L * 1024 * mb / 20, ArtCache.limitFor(10L * 1024 * mb))
        assertEquals(250 * mb, ArtCache.limitFor(2L * 1024 * mb))
    }

    private fun game(id: Long, platform: String = "psx", box: Boolean = false) = GameEntity(
        id = id, platformId = 1, platformSlug = platform, name = "Game $id", sortTitle = "game $id",
        fsName = null, fsSizeBytes = 0, coverPath = "/c/$id.png", hasMultipleFiles = false,
    ).copy(box2dPath = if (box) "/f/$id.png" else null, box2dSidePath = "/s/$id.png", box2dBackPath = if (box) "/b/$id.png" else null)

    @Test
    fun `fetching ahead goes outwards from the focus, with the box only when it's drawn`() {
        val games = listOf(game(0), game(1, box = true), game(2), game(3))
        assertEquals(listOf("/c/2.png", "/s/2.png", "/c/1.png", "/s/1.png", "/c/3.png", "/s/3.png", "/c/0.png", "/s/0.png"), ShelfPrefetch.pathsFrom(games, 2, fullBox = false))
        assertEquals(listOf("/c/1.png", "/s/1.png", "/f/1.png", "/b/1.png"), ShelfPrefetch.pathsFrom(listOf(game(1, box = true)), 0, fullBox = true))
    }
}
