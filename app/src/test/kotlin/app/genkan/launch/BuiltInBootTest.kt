package app.genkan.launch

import app.genkan.core.CoreCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Which file the built-in player starts from. Made-up games. */
class BuiltInBootTest {
    @get:Rule val temp = TemporaryFolder()

    private val yabaSanshiro = CoreCatalog.byId("yabasanshiro")!!
    private val beetleSaturn = CoreCatalog.byId("beetle_saturn")!!

    /** A two-disc game: its discs and the playlist naming them. */
    private fun twoDiscs(ext: String = "chd"): File {
        val folder = temp.newFolder("game")
        File(folder, "game (Disc 1).$ext").writeBytes(ByteArray(16))
        File(folder, "game (Disc 2).$ext").writeBytes(ByteArray(16))
        return File(folder, "game.m3u").apply { writeText("game (Disc 1).$ext\ngame (Disc 2).$ext\n") }
    }

    @Test
    fun `a core that opens playlists starts from the playlist`() {
        val playlist = twoDiscs()
        assertEquals(playlist, BuiltInBoot.fileFor(beetleSaturn, playlist))
    }

    @Test
    fun `a core that can't open playlists starts from the first disc listed`() {
        val playlist = twoDiscs()
        assertEquals(File(playlist.parentFile, "game (Disc 1).chd"), BuiltInBoot.fileFor(yabaSanshiro, playlist))
    }

    @Test
    fun `a playlist of discs the core can't play, or a file it can't, isn't the built-in player's`() {
        assertNull(BuiltInBoot.fileFor(yabaSanshiro, twoDiscs(ext = "nrg")))
        assertNull(BuiltInBoot.fileFor(yabaSanshiro, File(temp.root, "game.nrg").apply { writeBytes(ByteArray(16)) }))
    }

    @Test
    fun `a file the core plays is started as it is`() {
        val disc = File(temp.root, "game.chd").apply { writeBytes(ByteArray(16)) }
        assertEquals(disc, BuiltInBoot.fileFor(yabaSanshiro, disc))
    }
}
