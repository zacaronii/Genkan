package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class CoreSavesTest {

    private val storage = File("/storage")

    @Test
    fun `a GBA game shares RetroArch's mGBA save`() {
        val core = CoreCatalog.forPlatform("gba")!!
        assertEquals(
            File("/storage/RetroArch/saves/mGBA/Game (Japan).srm"),
            CoreSaves.fileFor("gba", core, File("/storage/ROMs/gba/Game (Japan).gba"), storage),
        )
    }

    @Test
    fun `a PS1 game saves in Genkan's own folder`() {
        val core = CoreCatalog.forPlatform("psx")!!
        assertEquals(
            File("/storage/Genkan/saves/pcsx_rearmed/Game (Japan).srm"),
            CoreSaves.fileFor("psx", core, File("/storage/ROMs/psx/Game (Japan).chd"), storage),
        )
    }
}
