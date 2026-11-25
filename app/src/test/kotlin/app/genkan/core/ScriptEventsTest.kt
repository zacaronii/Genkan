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
}
