package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
}
