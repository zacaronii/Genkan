package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreCatalogTest {

    private val gameCube = CoreCatalog.forPlatform("ngc")!!
    private val wii = CoreCatalog.forPlatform("wii")!!

    @Test
    fun `Dolphin gets a controller in port 1, the other cores keep their own default`() {
        // Dolphin's ports start with no device: without one the game never sees a GameCube pad.
        assertEquals(listOf(CoreCatalog.RETRO_DEVICE_JOYPAD), gameCube.portDevices)
        assertEquals(listOf(CoreCatalog.RETRO_DEVICE_JOYPAD), wii.portDevices)
        assertEquals(1, CoreCatalog.RETRO_DEVICE_JOYPAD)
        CoreCatalog.cores.filter { it.library != gameCube.library }.forEach { assertEquals(it.id, emptyList<Int>(), it.portDevices) }
    }

    @Test
    fun `a Wii plays on the GameCube's Dolphin, sharing its ladder and its folder`() {
        assertEquals("dolphin_wii", wii.id)
        assertEquals(gameCube.library, wii.library)
        assertEquals(gameCube.assets, wii.assets)
        assertEquals(gameCube.savesFolder, wii.savesFolder)
        assertTrue(wii.bigEndian && wii.sticksOnly)
        assertEquals("dolphin", wii.tuning)
        assertEquals("dolphin", gameCube.tuning)
        assertSame(TuningLadders.of(gameCube.tuning), TuningLadders.of(wii.tuning))
        assertNotNull(TuningLadders.of(wii.tuning))
        // Every core's own id is its tuning, but for the one sharing another's.
        CoreCatalog.cores.filter { it !== wii }.forEach { assertEquals(it.id, it.tuning) }
    }

    @Test
    fun `a Wii's disc images play, its channels don't`() {
        listOf("game.wbfs", "game.rvz", "game.iso", "game.wia", "game.gcz", "game.ciso").forEach { assertTrue(it, wii.takes(it)) }
        assertFalse(wii.takes("channel.wad"))
    }

    @Test
    fun `a Wii starts with a Wiimote and Nunchuk, its pointer on the touch screen, a wide Japanese console`() {
        assertEquals("nunchuk", wii.variables["dolphin_wiimote_default_device"])
        assertEquals("2", wii.variables["dolphin_ir_mode"])
        assertEquals("enabled", wii.variables["dolphin_widescreen"])
        assertEquals("0", wii.variables["dolphin_language"])
        assertEquals("0", wii.variables["dolphin_sensor_bar_position"])
        // The GameCube's variables are the Wii's too, so the ladder's defaults apply alike.
        gameCube.variables.forEach { (key, value) -> assertEquals(key, value, wii.variables[key]) }
    }

    @Test
    fun `only a Wii keeps its pointer where the finger left it`() {
        assertTrue(wii.keepPointer)
        CoreCatalog.cores.filter { it !== wii }.forEach { assertFalse(it.id, it.keepPointer) }
    }

    @Test
    fun `a Wii's pointers fold onto MEM1 and MEM2 as games see them cached`() {
        fun fold(address: Long) = address and wii.pointerMask
        assertEquals(0x80001234L, fold(0x80001234L))
        assertEquals(0x80001234L, fold(0xC0001234L))
        assertEquals(0x917F0000L, fold(0x917F0000L))
        assertEquals(0x93FFFFFCL, fold(0xD3FFFFFCL))
    }

    @Test
    fun `PC Engine HuCards and CD-ROM discs play on Beetle PCE Fast, CDs with RomM's System Card 3`() {
        val hucard = CoreCatalog.forPlatform("tg16")!!
        val cd = CoreCatalog.forPlatform("turbografx-cd")!!
        assertSame(hucard, cd)
        assertEquals("beetle_pce_fast", cd.id)
        assertEquals("libmednafen_pce_fast_libretro_android.so", cd.library)
        // RomM's file name to the one the core looks for in its system folder.
        assertEquals(mapOf("syscard3.pce" to "syscard3.pce"), cd.firmware)
        assertEquals("System Card 3", cd.variables["pce_fast_cdbios"])
        listOf("game.pce", "game.cue", "game.chd", "game.ccd", "game.toc", "games.m3u").forEach { assertTrue(it, cd.takes(it)) }
        assertFalse(cd.takes("game.zip"))
        // A light core: no ladder, its pad's own buttons, saves in Genkan's folder.
        assertNull(TuningLadders.of(cd.tuning))
        assertTrue(cd.portDevices.isEmpty() && !cd.sticksOnly && cd.savesFolder == null && !cd.keepPointer)
    }

    @Test
    fun `a Saturn plays on either debug-only candidate, as the debug choice says, and on none in a release`() {
        try {
            assertEquals("yabasanshiro", CoreCatalog.forPlatform("saturn", debug = true)!!.id)
            CoreCatalog.debugChoice = mapOf("saturn" to "beetle_saturn")
            assertEquals("beetle_saturn", CoreCatalog.forPlatform("saturn", debug = true)!!.id)
            // A choice of a core the console doesn't have falls back to its first.
            CoreCatalog.debugChoice = mapOf("saturn" to "mgba")
            assertEquals("yabasanshiro", CoreCatalog.forPlatform("saturn", debug = true)!!.id)
            assertNull(CoreCatalog.forPlatform("saturn", debug = false))
        } finally {
            CoreCatalog.debugChoice = emptyMap()
        }
        // Every other console's core is in releases too, but for the PS2's.
        CoreCatalog.cores.filter { "saturn" !in it.platforms && "ps2" !in it.platforms }.forEach { assertFalse(it.id, it.debugOnly) }
        assertEquals("mgba", CoreCatalog.forPlatform("gba", debug = false)!!.id)
    }

    @Test
    fun `both Saturn candidates boot with RomM's Japanese BIOS and take its discs, not zips`() {
        val saturn = CoreCatalog.cores.filter { "saturn" in it.platforms }
        assertEquals(listOf("yabasanshiro", "beetle_saturn"), saturn.map { it.id })
        saturn.forEach { core ->
            assertTrue(core.id, core.debugOnly)
            assertEquals(core.id, mapOf("sega_101.bin" to "sega_101.bin"), core.firmware)
            listOf("game.cue", "game.chd", "game.ccd").forEach { assertTrue("${core.id} $it", core.takes(it)) }
            assertFalse(core.id, core.takes("game.zip"))
            assertNull(core.id, TuningLadders.of(core.tuning))
            assertSame(core, CoreCatalog.byId(core.id))
        }
    }

    @Test
    fun `Dreamcast discs play on Flycast, with RomM's BIOS in its dc folder and its RAM at 0x0C000000`() {
        val dc = CoreCatalog.forPlatform("dc", debug = false)!!
        assertEquals("flycast", dc.id)
        assertEquals("libflycast_libretro_android.so", dc.library)
        assertEquals(mapOf("dc_boot.bin" to "dc/dc_boot.bin", "dc_flash.bin" to "dc/dc_flash.bin"), dc.firmware)
        assertEquals("Japan", dc.variables["reicast_region"])
        assertEquals("Japanese", dc.variables["reicast_language"])
        listOf("game.chd", "game.gdi", "game.cdi", "game.cue", "games.m3u").forEach { assertTrue(it, dc.takes(it)) }
        listOf("game.zip", "game.7z", "track01.bin").forEach { assertFalse(it, dc.takes(it)) }
        // Pointers through the cached and uncached mirrors fold onto the RAM's own addresses.
        assertEquals(0x0C000000L, dc.ramBase)
        assertEquals(0x0C123456L, 0x8C123456L and dc.pointerMask)
        assertEquals(0x0C123456L, 0xAC123456L and dc.pointerMask)
        assertNotNull(TuningLadders.of(dc.tuning))
    }

    @Test
    fun `the PS2 stays on ARMSX2 unless a debug build chooses LRPS2`() {
        try {
            // Not chosen: no built-in core, so the launcher keeps ARMSX2, in a debug build too.
            assertNull(CoreCatalog.forPlatform("ps2", debug = true))
            assertFalse("ps2" in CoreCatalog.platforms)
            CoreCatalog.debugChoice = mapOf("ps2" to "lrps2")
            assertEquals("lrps2", CoreCatalog.forPlatform("ps2", debug = true)!!.id)
            assertNull(CoreCatalog.forPlatform("ps2", debug = false))
        } finally {
            CoreCatalog.debugChoice = emptyMap()
        }
        // Opting in is the PS2's alone: the Saturn's candidates play without a choice.
        CoreCatalog.cores.filter { it.optIn }.forEach { assertEquals(setOf("ps2"), it.platforms) }
    }

    @Test
    fun `LRPS2 boots with RomM's BIOS in its own folder, draws as its ladder says and reads the EE's 32 MB`() {
        val ps2 = CoreCatalog.byId("lrps2")!!
        assertEquals("libpcsx2_libretro_android.so", ps2.library)
        assertTrue(ps2.debugOnly)
        assertEquals(mapOf("ps2-0230a-20080220.bin" to "pcsx2/bios/ps2-0230a-20080220.bin"), ps2.firmware)
        assertEquals("ps2-0230a-20080220.bin", ps2.variables["pcsx2_bios"])
        // The renderer is the ladder's: Software on 6 threads by default, GL ("Auto") as the lighter step.
        assertNull(ps2.variables["pcsx2_renderer"])
        val ladder = TuningLadders.of(ps2.tuning)!!
        assertEquals(mapOf("pcsx2_renderer" to "Software (HW)", "pcsx2_sw_renderer_threads" to "6"), ladder.assignment("default"))
        assertEquals("Auto", ladder.assignment("gl")["pcsx2_renderer"])
        listOf("game.chd", "game.iso", "game.cso", "games.m3u").forEach { assertTrue(it, ps2.takes(it)) }
        assertFalse(ps2.takes("game.zip"))
        assertEquals(0L, ps2.ramBase)
        assertEquals(0x00123450L, 0x20123450L and ps2.pointerMask)
        assertEquals(0x00123450L, 0x30123450L and ps2.pointerMask)
        // The pad has stick clicks of its own.
        assertFalse(BottomCycle.takesStickClicks("ps2"))
    }
}
