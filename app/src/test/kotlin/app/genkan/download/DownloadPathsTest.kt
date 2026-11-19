package app.genkan.download

import app.genkan.romm.RomMRomFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadPathsTest {

    private val root = File("/sdcard/ROMs")

    private fun file(name: String, path: String) =
        RomMRomFile(id = 1, romId = 1, fileName = name, filePath = path, fileSizeBytes = 1, fullPath = "$path/$name")

    @Test
    fun `single files go straight into the platform folder`() {
        assertEquals(File("/sdcard/ROMs/psx/Crash Bandicoot (USA).chd"), DownloadPaths.gamePath(root, "psx", "Crash Bandicoot (USA).chd"))
    }

    @Test
    fun `multi-file games keep the server layout below the game folder`() {
        val game = File("/sdcard/ROMs/ps2/Xenosaga II")
        val disc = file(".Xenosaga II (Disc 1).chd", "roms/ps2/Xenosaga II")
        val update = file("patch.nsp", "roms/switch/Game/updates")
        assertEquals(File(game, ".Xenosaga II (Disc 1).chd"), DownloadPaths.fileInGame(game, "roms/ps2/Xenosaga II", disc))
        assertEquals(File("/x/updates/patch.nsp"), DownloadPaths.fileInGame(File("/x"), "roms/switch/Game", update))
    }

    @Test
    fun `hidden disc images are game files but NAS sidecars are not`() {
        assertTrue(DownloadPaths.isGameFile(file(".Game (Disc 1).chd", "roms/ps2/Game")))
        assertFalse(DownloadPaths.isGameFile(file("thumb.jpg", "roms/ps2/Game/@eaDir")))
    }

    @Test
    fun `server-supplied names cannot escape the downloads folder`() {
        assertEquals("_", DownloadPaths.safe(".."))
        assertEquals("a_b", DownloadPaths.safe("a/b"))
        val sneaky = file("x.bin", "roms/ps2/Game/../../..")
        val placed = DownloadPaths.fileInGame(File("/sdcard/ROMs/ps2/Game"), "roms/ps2/Game", sneaky)
        assertTrue(placed.path, placed.path.startsWith("/sdcard/ROMs/ps2/Game/"))
        assertFalse(placed.path.contains("/../"))
    }
}
