package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerChoiceTest {

    @Test
    fun `the PS2 plays on LRPS2 by default, with ARMSX2 its other way`() {
        assertEquals(
            listOf(PlayerChoice.Option("lrps2", "LRPS2 (built in)"), PlayerChoice.Option(CoreCatalog.EXTERNAL, "ARMSX2")),
            PlayerChoice.options("ps2", debug = false),
        )
        assertEquals("lrps2", PlayerChoice.current("ps2", emptyMap(), debug = false)!!.value)
        assertEquals(CoreCatalog.EXTERNAL, PlayerChoice.current("ps2", mapOf("ps2" to CoreCatalog.EXTERNAL), debug = false)!!.value)
    }

    @Test
    fun `a press moves to the next way, round to the first`() {
        assertEquals(CoreCatalog.EXTERNAL, PlayerChoice.next("ps2", emptyMap(), debug = false)!!.value)
        assertEquals("lrps2", PlayerChoice.next("ps2", mapOf("ps2" to CoreCatalog.EXTERNAL), debug = false)!!.value)
        // A choice that isn't one of its ways (a core since dropped) counts as the first.
        assertEquals("lrps2", PlayerChoice.current("ps2", mapOf("ps2" to "gone"), debug = false)!!.value)
    }

    @Test
    fun `the 3DS stays with the Azahar app until the built-in core is chosen`() {
        assertEquals(
            listOf(PlayerChoice.Option(CoreCatalog.EXTERNAL, "Azahar"), PlayerChoice.Option("azahar_libretro", "Azahar (built in)")),
            PlayerChoice.options("3ds", debug = false),
        )
        assertEquals(CoreCatalog.EXTERNAL, PlayerChoice.current("3ds", emptyMap(), debug = false)!!.value)
        // Nothing chosen: no built-in core plays it, so the launcher takes the app.
        assertEquals(null, CoreCatalog.forPlatform("3ds", debug = false).also { CoreCatalog.choice = emptyMap() })
        assertEquals("azahar_libretro", PlayerChoice.next("3ds", emptyMap(), debug = false)!!.value)
        try {
            CoreCatalog.choice = mapOf("3ds" to "azahar_libretro")
            assertEquals("azahar_libretro", CoreCatalog.forPlatform("3ds", debug = false)!!.id)
        } finally {
            CoreCatalog.choice = emptyMap()
        }
    }

    @Test
    fun `only consoles with more than one way get a row`() {
        assertEquals(listOf("ps2", "3ds"), PlayerChoice.consoles(debug = false).map { it.first })
        assertEquals(listOf("ps2", "3ds"), PlayerChoice.consoles(debug = true).map { it.first })
        // The Saturn has one way: Beetle Saturn, with no emulator app known.
        assertEquals(listOf("beetle_saturn"), PlayerChoice.options("saturn", debug = false).map { it.value })
    }
}
