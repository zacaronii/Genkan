package app.genkan.core

import app.genkan.core.ResolvedTuning.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TuningTest {
    private val dolphin = TuningLadders.of("dolphin")!!
    private val here = "Made-up Handheld"

    private fun resolve(override: String? = null, bundled: BundledTuning? = null) = TuningLadders.resolve(dolphin, override, bundled, here)

    @Test
    fun `a step sets its own keys and puts the rest back to the core's defaults`() {
        assertEquals(
            mapOf("dolphin_efb_scale" to "2", "dolphin_vi_skip" to "disabled", "dolphin_cpu_clock_rate" to "1.00"),
            dolphin.assignment("efb-2x"),
        )
        assertEquals(dolphin.defaults, dolphin.assignment("default"))
    }

    @Test
    fun `only cores that need one have a ladder, and only Dolphin's EFB steps apply as the game runs`() {
        assertNull(TuningLadders.of("mgba"))
        assertEquals(listOf("efb-2x", "default"), dolphin.steps.filter { it.live }.map { it.id })
        assertFalse(TuningLadders.of("ppsspp")!!.steps.any { it.live })
        assertFalse(TuningLadders.of("flycast")!!.steps.any { it.live })
    }

    @Test
    fun `Flycast's ladder is its resolution, sharpest first, and frame skip only as a risky last step`() {
        val flycast = TuningLadders.of("flycast")!!
        assertEquals(listOf("1920x1440", "1280x960", "default", "320x240", "auto-skip"), flycast.steps.map { it.id })
        assertEquals("default", flycast.defaultId)
        assertEquals(listOf("auto-skip"), flycast.steps.filter { it.risky }.map { it.id })
        assertEquals(mapOf("reicast_internal_resolution" to "640x480", "reicast_auto_skip_frame" to "disabled"), flycast.assignment("default"))
        assertEquals(mapOf("reicast_internal_resolution" to "1280x960", "reicast_auto_skip_frame" to "disabled"), flycast.assignment("1280x960"))
        assertEquals("1×", flycast.step("default")!!.label)
    }

    @Test
    fun `nothing set is the core's default`() {
        assertEquals(ResolvedTuning(dolphin.default, Source.DEFAULT), resolve())
    }

    @Test
    fun `the device's own step wins over the catalog's`() {
        assertEquals(ResolvedTuning(dolphin.step("efb-2x")!!, Source.OVERRIDE), resolve("efb-2x", BundledTuning("default", null)))
    }

    @Test
    fun `the device's own step is never a risky one, and an unknown one counts as none`() {
        assertEquals(Source.DEFAULT, resolve("vi-skip").source)
        assertEquals(ResolvedTuning(dolphin.step("efb-2x")!!, Source.CATALOG), resolve("no-such-step", BundledTuning("efb-2x", null)))
    }

    @Test
    fun `a risky catalog step counts only where it was measured`() {
        assertEquals("vi-skip-cpu-80", resolve(bundled = BundledTuning("vi-skip-cpu-80", here)).step.id)
        // Elsewhere, or measured nowhere: the safe step nearest it on the way to the default.
        assertEquals("default", resolve(bundled = BundledTuning("vi-skip-cpu-80", "Another Handheld")).step.id)
        assertEquals("default", resolve(bundled = BundledTuning("vi-skip", null)).step.id)
        assertEquals("default", resolve(bundled = BundledTuning("vi-skip", " ")).step.id)
    }

    @Test
    fun `a safe catalog step counts anywhere, and an unknown one falls through`() {
        assertEquals(ResolvedTuning(dolphin.step("efb-2x")!!, Source.CATALOG), resolve(bundled = BundledTuning("efb-2x", null)))
        assertEquals(Source.DEFAULT, resolve(bundled = BundledTuning("8k", here)).source)
    }

    @Test
    fun `the safe step toward the default stops at the first safe one`() {
        val ladder = TuningLadder(
            steps = listOf(
                TuningStep("up", mapOf("k" to "u")),
                TuningStep("base", emptyMap()),
                TuningStep("light", mapOf("k" to "l")),
                TuningStep("lighter", mapOf("k" to "x"), risky = true),
            ),
            defaultId = "base",
            defaults = mapOf("k" to "d"),
        )
        assertEquals("light", ladder.safeToward("lighter").id)
        assertEquals("up", ladder.safeToward("up").id)
        assertEquals("base", ladder.safeToward("missing").id)
    }
}
