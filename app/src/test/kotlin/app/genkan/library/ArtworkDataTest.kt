package app.genkan.library

import app.genkan.romm.RomMApiFactory
import app.genkan.romm.RomMRom
import app.genkan.romm.RomMSsMetadata
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** A made-up game in RomM's JSON shape, parsed and mapped to what the library stores. */
class ArtworkDataTest {

    private val fixture = javaClass.getResource("/romm/rom-artwork-fixture.json")!!.readText()
    private val rom: RomMRom = Moshi.Builder().build().adapter(RomMRom::class.java).fromJson(fixture)!!
    private val game = LibrarySync.toEntity(rom)

    @Test
    fun `screenscraper paths are stored with the resources prefix`() {
        val base = "/assets/romm/resources/roms/7/4242"
        assertEquals("$base/physical/physical.png", game.physicalPath)
        assertEquals("$base/logo/logo.png", game.logoPath)
        assertEquals("$base/box2d/box2d.png", game.box2dPath)
        assertEquals("$base/box2d_side/box2d_side.png", game.box2dSidePath)
        assertEquals("$base/box2d_back/box2d_back.png", game.box2dBackPath)
        assertEquals("$base/box3d/box3d.png", game.box3dPath)
        assertEquals("$base/title_screen/title_screen.png", game.titleScreenPath)
        assertEquals("$base/marquee/marquee.png", game.marqueePath)
        assertEquals("$base/fanart/fanart.jpg", game.fanartPath)
    }

    @Test
    fun `already prefixed paths are kept as they are`() {
        assertEquals(rom.coverLarge, game.coverLargePath)
        assertEquals(
            listOf(
                "/assets/romm/resources/roms/7/4242/screenshots/0.jpg",
                "/assets/romm/resources/roms/7/4242/screenshots/1.jpg",
            ),
            game.screenshotPathList,
        )
        assertEquals("/assets/romm/resources/roms/7/4242/manual/4242.pdf", game.manualPath)
    }

    @Test
    fun `stored paths become urls the same way covers do`() {
        assertEquals(
            "https://romm.example.com/assets/romm/resources/roms/7/4242/physical/physical.png",
            RomMApiFactory.assetUrl("https://romm.example.com/", game.physicalPath),
        )
        assertEquals(
            "https://romm.example.com/assets/romm/resources/roms/7/4242/cover/big.png?ts=2026-01-02%2003:04:05",
            RomMApiFactory.assetUrl("https://romm.example.com/", game.coverLargePath),
        )
    }

    @Test
    fun `no screenscraper url is parsed or stored`() {
        assertFalse("EXAMPLE_SECRET" in rom.ssMetadata.toString())
        assertFalse("EXAMPLE_SECRET" in game.toString())
        assertFalse("scraper.example.com" in game.toString())
        val urlFields = RomMSsMetadata::class.java.declaredFields.filter { it.name.endsWith("Url", ignoreCase = true) }
        assertTrue("RomMSsMetadata must not model *_url fields: $urlFields", urlFields.isEmpty())
    }

    @Test
    fun `details are stored`() {
        assertEquals(listOf("Japan"), GameEntity.splitList(game.regions))
        assertEquals(listOf("Example Saga"), GameEntity.splitList(game.franchises))
        assertEquals(listOf("Sample Soft", "Placeholder Inc."), GameEntity.splitList(game.publishers))
        assertEquals(listOf("Single player", "Multiplayer"), GameEntity.splitList(game.gameModes))
        assertEquals("1-2", game.playerCount)
        assertEquals(81.5f, game.averageRating!!, 0.001f)
        assertEquals(851990400000L, game.releaseDateMillis)
        assertEquals(1996, game.releaseYear)
        assertEquals("exampleVid01", game.youtubeVideoId)
        assertEquals("A made-up game for the parser tests.", game.summary)
        assertEquals(2, game.discCount)
    }

    @Test
    fun `howlongtobeat zeros are unknown`() {
        assertEquals(98765L, game.hltbId)
        assertEquals(108000, game.hltbMainStorySec)
        assertEquals(151200, game.hltbMainPlusExtraSec)
        assertNull(game.hltbCompletionistSec)
        assertEquals(140400, game.hltbAllStylesSec)
        assertEquals(78, game.hltbReviewScore)
    }

    @Test
    fun `an empty howlongtobeat object stores nothing`() {
        val bare = rom.copy(hltbId = null, hltbMetadata = Moshi.Builder().build()
            .adapter(app.genkan.romm.RomMHltbMetadata::class.java).fromJson("{}"))
        val entity = LibrarySync.toEntity(bare)
        assertNull(entity.hltbId)
        assertNull(entity.hltbMainStorySec)
        assertNull(entity.hltbReviewScore)
    }

    @Test
    fun `the user's own data is stored`() {
        assertEquals(Instant.parse("2026-03-04T05:06:07.123Z").toEpochMilli(), game.lastPlayedMillis)
        assertTrue(game.nowPlaying)
        assertFalse(game.backlogged)
        assertFalse(game.userHidden)
        assertEquals(8, game.userRating)
        assertEquals(4, game.userDifficulty)
        assertEquals(60, game.userCompletion)
        assertEquals("incomplete", game.userStatus)
    }

    @Test
    fun `a game without the new data maps to empty columns`() {
        val bare = LibrarySync.toEntity(RomMRom(id = 1, platformId = 2, platformSlug = "gba", name = "Plain"))
        assertNull(bare.physicalPath)
        assertNull(bare.coverLargePath)
        assertNull(bare.screenshotPaths)
        assertNull(bare.discCount)
        assertNull(bare.lastPlayedMillis)
        assertEquals(0, bare.userRating)
        assertTrue(bare.screenshotPathList.isEmpty())
    }

    @Test
    fun `a manual the server doesn't have is not stored`() {
        assertNull(LibrarySync.toEntity(rom.copy(hasManual = false)).manualPath)
    }

    @Test
    fun `resource paths`() {
        assertEquals("/assets/romm/resources/roms/1/a.png", RomMApiFactory.resourcePath("roms/1/a.png"))
        assertEquals("/assets/romm/resources/roms/1/a.png", RomMApiFactory.resourcePath("/roms/1/a.png"))
        assertEquals("/assets/romm/resources/roms/1/a.png", RomMApiFactory.resourcePath("/assets/romm/resources/roms/1/a.png"))
        assertEquals("/assets/romm/resources/roms/1/a.png", RomMApiFactory.resourcePath("assets/romm/resources/roms/1/a.png"))
        assertNull(RomMApiFactory.resourcePath("https://scraper.example.com/a.png?devpassword=x"))
        assertNull(RomMApiFactory.resourcePath(""))
        assertNull(RomMApiFactory.resourcePath(null))
    }

    @Test
    fun `timestamps without an offset are utc`() {
        assertEquals(Instant.parse("2026-03-04T05:06:07Z").toEpochMilli(), LibrarySync.parseTimestamp("2026-03-04T05:06:07"))
        assertNull(LibrarySync.parseTimestamp("not a date"))
    }

    /** The same game after RomM changed it (re-matched, say): the fixture with an update time. */
    private fun updated(at: String): RomMRom =
        Moshi.Builder().build().adapter(RomMRom::class.java).fromJson(fixture.replaceFirst("{", "{\"updated_at\": \"$at\","))!!

    @Test
    fun `screenscraper art, screenshots and the manual carry the rom's update time`() {
        val changed = LibrarySync.toEntity(updated("2026-11-22T23:39:35+00:00"))
        val base = "/assets/romm/resources/roms/7/4242"
        assertEquals("$base/box2d/box2d.png?ts=20261122233935", changed.box2dPath)
        assertEquals("$base/box2d_side/box2d_side.png?ts=20261122233935", changed.box2dSidePath)
        assertEquals("$base/box2d_back/box2d_back.png?ts=20261122233935", changed.box2dBackPath)
        assertEquals("$base/physical/physical.png?ts=20261122233935", changed.physicalPath)
        assertEquals("$base/logo/logo.png?ts=20261122233935", changed.logoPath)
        assertEquals("$base/fanart/fanart.jpg?ts=20261122233935", changed.fanartPath)
        assertEquals("$base/screenshots/0.jpg?ts=20261122233935", changed.screenshotPathList.first())
        assertEquals("$base/manual/4242.pdf?ts=20261122233935", changed.manualPath)
        // Covers keep RomM's own version.
        assertEquals(rom.coverLarge, changed.coverLargePath)
    }

    @Test
    fun `a game re-matched in RomM gets new art paths, so the disk cache can't serve the old files`() {
        val before = LibrarySync.toEntity(updated("2026-11-21T22:33:37+00:00"))
        val after = LibrarySync.toEntity(updated("2026-11-22T23:39:35+00:00"))
        for (path in listOf<(app.genkan.library.GameEntity) -> String?>({ it.box2dPath }, { it.box2dSidePath }, { it.box2dBackPath }, { it.physicalPath })) {
            assertTrue(path(before) != path(after))
        }
    }

    @Test
    fun `a version joins a query already there, and nothing is added without one`() {
        assertEquals("/a.png?x=1&ts=20261122233935", LibrarySync.versioned("/a.png?x=1", "2026-11-22T23:39:35+00:00"))
        assertEquals("/a.png", LibrarySync.versioned("/a.png", null))
        assertEquals("/a.png", LibrarySync.versioned("/a.png", ""))
        assertNull(LibrarySync.versioned(null, "2026-11-22T23:39:35+00:00"))
    }
}
