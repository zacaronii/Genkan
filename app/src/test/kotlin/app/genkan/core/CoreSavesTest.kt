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

    @Test
    fun `PSP saves go on PPSSPP's memory stick when it has one`() {
        val storage = kotlin.io.path.createTempDirectory().toFile()
        val core = CoreCatalog.forPlatform("psp")!!
        val fallback = File(storage, "private")
        assertEquals(fallback, CoreSaves.directoryFor(core, fallback, storage))
        File(storage, "ppsspp/PSP/SAVEDATA").mkdirs()
        assertEquals(File(storage, "ppsspp"), CoreSaves.directoryFor(core, fallback, storage))
        storage.deleteRecursively()
    }
}
