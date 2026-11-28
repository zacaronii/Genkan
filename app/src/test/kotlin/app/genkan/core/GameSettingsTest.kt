package app.genkan.core

import app.genkan.core.ResolvedTuning.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class GameSettingsTest {
    private val lrps2 = TuningLadders.of("lrps2")!!
    private val ps2 = CoreCatalog.cores.single { it.id == "lrps2" }
    private val options = CoreOptions.of("lrps2").filter { it.key in setOf("pcsx2_upscale_multiplier", "pcsx2_mtvu", "pcsx2_blending_accuracy") }

    private fun rows(resolved: ResolvedTuning, console: Map<String, String> = emptyMap(), game: Map<String, String> = emptyMap()) =
        GameSettings.rows(ps2, lrps2, resolved, options, console, game).associate { it.key to (it.value to it.source) }

    @Test
    fun `each row says where its value comes from, this game over its console over the default`() {
        val rows = rows(
            ResolvedTuning(lrps2.default, Source.DEFAULT),
            console = mapOf("pcsx2_upscale_multiplier" to "2x", "pcsx2_mtvu" to "disabled"),
            game = mapOf("pcsx2_upscale_multiplier" to "4x"),
        )
        assertEquals("4×" to SettingSource.GAME, rows["pcsx2_upscale_multiplier"])
        assertEquals("Off" to SettingSource.CONSOLE, rows["pcsx2_mtvu"])
        assertEquals("Basic" to SettingSource.DEFAULT, rows["pcsx2_blending_accuracy"])
    }

    @Test
    fun `the renderer's row says whether this game chose it, learning did, the catalog or nothing`() {
        assertEquals("GL" to SettingSource.GAME, rows(ResolvedTuning(lrps2.step("gl")!!, Source.CHOSEN))[GameSettings.GRAPHICS])
        assertEquals("Software" to SettingSource.LEARNER, rows(ResolvedTuning(lrps2.step("software")!!, Source.OVERRIDE))[GameSettings.GRAPHICS])
        assertEquals("GL" to SettingSource.CATALOG, rows(ResolvedTuning(lrps2.step("gl")!!, Source.CATALOG))[GameSettings.GRAPHICS])
        assertEquals("Vulkan" to SettingSource.DEFAULT, rows(ResolvedTuning(lrps2.default, Source.DEFAULT))[GameSettings.GRAPHICS])
    }

    @Test
    fun `a learned step that sets an option shows it as learned, unless the game or its console pinned it`() {
        val dolphin = TuningLadders.of("dolphin")!!
        val gc = CoreCatalog.cores.single { it.id == "dolphin" }
        val efb = CoreOption("dolphin", "dolphin_efb_scale", "Resolution", listOf(OptionValue("1", "1×"), OptionValue("2", "2×")), "1")
        val learned = ResolvedTuning(dolphin.step("efb-2x")!!, Source.OVERRIDE)
        assertEquals(SettingRow("dolphin_efb_scale", "Resolution", "2×", SettingSource.LEARNER), GameSettings.rows(gc, dolphin, learned, listOf(efb), emptyMap(), emptyMap()).single())
        assertEquals(SettingSource.CONSOLE, GameSettings.rows(gc, dolphin, learned, listOf(efb), mapOf("dolphin_efb_scale" to "1"), emptyMap()).single().source)
        // Dolphin has no choice of renderers: no Graphics row.
        assertEquals(listOf("dolphin_efb_scale"), GameSettings.rows(gc, dolphin, learned, listOf(efb), emptyMap(), emptyMap()).map { it.key })
    }
}
