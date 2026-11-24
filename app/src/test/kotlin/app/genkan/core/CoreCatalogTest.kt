package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreCatalogTest {

    @Test
    fun `Dolphin gets a controller in port 1, the other cores keep their own default`() {
        // Dolphin's ports start with no device: without one the game never sees a GameCube pad.
        assertEquals(listOf(CoreCatalog.RETRO_DEVICE_JOYPAD), CoreCatalog.forPlatform("ngc")!!.portDevices)
        assertEquals(1, CoreCatalog.RETRO_DEVICE_JOYPAD)
        CoreCatalog.cores.filter { it.id != "dolphin" }.forEach { assertEquals(it.id, emptyList<Int>(), it.portDevices) }
    }
}
