package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CoreOptionsTest {
    @get:Rule val folder = TemporaryFolder()

    private val lrps2 = TuningLadders.of("lrps2")!!
    private val dolphin = TuningLadders.of("dolphin")!!

    @Test
    fun `each core's options are its own, keyed once, each default one of its values, and none offered until measured`() {
        CoreOptions.all.groupBy { it.core }.forEach { (core, options) ->
            assertEquals(core, options.size, options.map { it.key }.toSet().size)
            assertTrue(core, CoreCatalog.cores.any { it.id == core })
        }
        assertTrue(CoreOptions.of("lrps2").isNotEmpty())
        assertTrue(CoreOptions.offered("lrps2").isEmpty())
    }

    @Test
    fun `a press moves an option through unset and each of its values`() {
        val option = CoreOptions.option("lrps2", "pcsx2_upscale_multiplier")!!
        var at: String? = null
        val seen = List(4) { option.next(at).also { at = it } }
        assertEquals(listOf("1x (Native)", "2x", "4x", null), seen)
        assertEquals("1x (Native)", option.next("8x"))
    }

    @Test
    fun `pins are kept for a console and a game, the game's over the console's, and a game's clear at once`() {
        val dir = File(folder.root, "tuning")
        OptionPins(dir).setConsole("ps2", "pcsx2_upscale_multiplier", "2x")
        OptionPins(dir).setGame(42, "pcsx2_upscale_multiplier", "1x (Native)")
        OptionPins(dir).setGame(42, "pcsx2_mtvu", "disabled")
        val pins = OptionPins(dir)
        assertEquals(
            mapOf("pcsx2_upscale_multiplier" to "1x (Native)", "pcsx2_mtvu" to "disabled"),
            OptionPins.applied(pins.console("ps2"), pins.game(42)),
        )
        assertEquals(mapOf("pcsx2_upscale_multiplier" to "2x"), OptionPins.applied(pins.console("ps2"), pins.game(7)))
        assertEquals(setOf(42L), pins.games().keys)
        pins.setGame(42, "pcsx2_mtvu", null)
        assertEquals(mapOf("pcsx2_upscale_multiplier" to "1x (Native)"), OptionPins(dir).game(42))
        pins.clearGame(42)
        assertTrue(OptionPins(dir).games().isEmpty())
        assertEquals(mapOf("pcsx2_upscale_multiplier" to "2x"), OptionPins(dir).console("ps2"))
    }

    @Test
    fun `pins travel to the player whole, and anything else is none`() {
        val pins = mapOf("pcsx2_upscale_multiplier" to "2x", "pcsx2_mtvu" to "disabled")
        assertEquals(pins, OptionPins.decode(OptionPins.encode(pins)))
        assertTrue(OptionPins.decode(null).isEmpty())
        assertTrue(OptionPins.decode("{not json").isEmpty())
        File(folder.root, "tuning").apply { mkdirs() }.resolve(OptionPins.FILE).writeText("{not json")
        assertTrue(OptionPins(File(folder.root, "tuning")).console("ps2").isEmpty())
    }

    @Test
    fun `a pinned key takes the steps that set it out of the learner's ladder, never the default`() {
        val pinned = dolphin.without(setOf("dolphin_efb_scale"))
        assertEquals(listOf("default", "vi-skip", "vi-skip-cpu-80"), pinned.steps.map { it.id })
        assertEquals(dolphin, dolphin.without(emptySet()))
        // LRPS2's Software step sets its renderer, which no option pins: its ladder stays whole.
        assertEquals(lrps2.steps, lrps2.without(CoreOptions.of("lrps2").map { it.key }.toSet()).steps)
    }

    @Test
    fun `the learner never moves a game onto a step that changes a pinned option`() {
        val learner = TuningLearner(dolphin.without(setOf("dolphin_efb_scale")), "default")
        val full = TuningLearner.Second(speed = 1.0, busiest = 0.2, gpuBusyPercent = 20, hottestC = 50, fastestMhz = 3000)
        val decisions = (1..(TuningLearner.SETTLE + TuningLearner.ROOM * 3)).mapNotNull { learner.second(full) }
        assertTrue(decisions.none { it.step == "efb-2x" })
        assertEquals("default", learner.step)
    }
}
