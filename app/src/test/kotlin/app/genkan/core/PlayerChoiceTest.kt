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
    fun `only consoles with more than one way get a row`() {
        assertEquals(listOf("ps2"), PlayerChoice.consoles(debug = false).map { it.first })
        assertEquals(listOf("ps2"), PlayerChoice.consoles(debug = true).map { it.first })
        // The Saturn has one way: Beetle Saturn, with no emulator app known.
        assertEquals(listOf("beetle_saturn"), PlayerChoice.options("saturn", debug = false).map { it.value })
    }
}
