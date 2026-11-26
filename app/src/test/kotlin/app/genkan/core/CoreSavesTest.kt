package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `only saves in Genkan's own folder sync as the core's`() {
        val psx = CoreCatalog.forPlatform("psx")!!
        val target = CoreSaves.ownTarget("psx", psx, File("/storage/ROMs/psx/Game (Japan).chd"), storage)!!
        assertEquals("pcsx_rearmed", target.emulator)
        assertEquals(File("/storage/Genkan/saves/pcsx_rearmed/Game (Japan).srm"), target.path)
        assertEquals("Game (Japan).srm", target.name)
        assertNull(CoreSaves.ownTarget("gba", CoreCatalog.forPlatform("gba")!!, File("/storage/ROMs/gba/Game.gba"), storage))
        assertNull(CoreSaves.ownTarget("psp", CoreCatalog.forPlatform("psp")!!, File("/storage/ROMs/psp/Game.iso"), storage))
        assertEquals("melonds", CoreSaves.ownTarget("nds", CoreCatalog.forPlatform("nds")!!, File("/storage/ROMs/nds/Game.nds"), storage)?.emulator)
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

    @Test
    fun `Dolphin keeps its User folder in Genkan's saves, and syncs only its memory cards`() {
        val core = CoreCatalog.forPlatform("ngc")!!
        assertEquals(File(storage, "Genkan/saves/dolphin"), CoreSaves.directoryFor(core, File(storage, "private"), storage))
        // Its GCI files sync as standalone Dolphin's, through the watched folders, not as an .srm.
        assertNull(CoreSaves.ownTarget("ngc", core, File("/storage/ROMs/ngc/Game.rvz"), storage))
        val watched = app.genkan.saves.WatchedEmulator.BUILT_IN.getValue("ngc")
        assertEquals("dolphin", watched.emulator)
        assertEquals(listOf("Genkan/saves/dolphin/User"), watched.bases)
        // Never its config or shader cache, beside GC in the User folder.
        assertEquals(listOf("GC"), watched.folders)
        assertEquals(app.genkan.saves.WatchedEmulator.BY_PLATFORM.getValue("ngc").folders, watched.folders)
    }

    @Test
    fun `a Wii game's saves sync from the same User folder's NAND, as standalone Dolphin's`() {
        val core = CoreCatalog.forPlatform("wii")!!
        assertEquals(File(storage, "Genkan/saves/dolphin"), CoreSaves.directoryFor(core, File(storage, "private"), storage))
        assertNull(CoreSaves.ownTarget("wii", core, File("/storage/ROMs/wii/Game.rvz"), storage))
        val watched = app.genkan.saves.WatchedEmulator.BUILT_IN.getValue("wii")
        assertEquals("dolphin", watched.emulator)
        assertEquals(app.genkan.saves.WatchedEmulator.BUILT_IN.getValue("ngc").bases, watched.bases)
        // Only the NAND's saves, never its system files or the GameCube's cards.
        assertEquals(listOf("Wii/title"), watched.folders)
        assertEquals(app.genkan.saves.WatchedEmulator.BY_PLATFORM.getValue("wii").folders, watched.folders)
    }

    @Test
    fun `Flycast keeps each game's VMU on shared storage, where the save sync watches it`() {
        val core = CoreCatalog.forPlatform("dc", debug = false)!!
        // Each game's own VMU, by its product number, not one for every game in the system folder.
        assertEquals("VMU A1", core.variables["reicast_per_content_vmus"])
        assertEquals(File(storage, "Genkan/saves/flycast"), CoreSaves.directoryFor(core, File(storage, "private"), storage))
        // The VMU files sync through the watched folder, not as an .srm.
        assertNull(CoreSaves.ownTarget("dc", core, File("/storage/ROMs/dc/Game.chd"), storage))
        val watched = app.genkan.saves.WatchedEmulator.BUILT_IN.getValue("dc")
        assertEquals("flycast", watched.emulator)
        assertEquals(core.savesFolder, "${watched.bases.single()}/${watched.folders.single()}")
        assertFalse(watched.privileged)
    }
}
