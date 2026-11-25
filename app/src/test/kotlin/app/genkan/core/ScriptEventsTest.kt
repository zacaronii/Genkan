package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptEventsTest {

    @Test
    fun `scripts get the catalog's platform names, where RomM's differ`() {
        assertEquals("gc", ScriptEvents.catalogPlatform("ngc"))
        assertEquals("ps1", ScriptEvents.catalogPlatform("psx"))
        assertEquals("sfc", ScriptEvents.catalogPlatform("snes"))
        assertEquals("sfc", ScriptEvents.catalogPlatform("sfam"))
        assertEquals("psp", ScriptEvents.catalogPlatform("psp"))
    }

    @Test
    fun `event kinds have names for the log`() {
        assertEquals(listOf("text", "layout", "cursor", "log", "kind 9"), listOf(1, 2, 3, 4, 9).map(ScriptEvents::kindName))
    }

    @Test
    fun `a build is a GameCube disc's id and version, or a cartridge's header CRC`() {
        val ram = ByteArray(0x40)
        "GTST01".toByteArray().copyInto(ram)
        byteArrayOf(0xC2.toByte(), 0x33, 0x9F.toByte(), 0x3D).copyInto(ram, 0x1C)
        assertEquals("GTST01-00", ScriptEvents.buildId("ngc", ram, null))
        assertEquals("1a2b3c4d", ScriptEvents.buildId("snes", null, "1A2B3C4D"))
        assertEquals(null, ScriptEvents.buildId("psx", ByteArray(16), null))
    }
}
