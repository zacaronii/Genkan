package app.genkan.core

import app.genkan.core.ResolvedTuning.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameGraphicsTest {
    @get:Rule val folder = TemporaryFolder()

    private val lrps2 = TuningLadders.of("lrps2")!!

    @Test
    fun `a game's own graphics are kept, read again from the file, listed, and go back to Automatic`() {
        val dir = File(folder.root, "tuning")
        GameGraphics(dir).set(42, "gl")
        GameGraphics(dir).set(7, "software")
        assertEquals("gl", GameGraphics(dir).choice(42))
        assertEquals(mapOf(42L to "gl", 7L to "software"), GameGraphics(dir).all())
        GameGraphics(dir).set(42, null)
        assertNull(GameGraphics(dir).choice(42))
        assertEquals(mapOf(7L to "software"), GameGraphics(dir).all())
    }

    @Test
    fun `no file, or one that doesn't read, is Automatic for every game`() {
        val dir = File(folder.root, "tuning").apply { mkdirs() }
        assertNull(GameGraphics(dir).choice(42))
        File(dir, GameGraphics.FILE).writeText("{not json")
        assertNull(GameGraphics(dir).choice(42))
        assertTrue(GameGraphics(dir).all().isEmpty())
    }

    @Test
    fun `the PS2 offers Vulkan, GL and Software, and no other ladder offers renderers`() {
        assertEquals(listOf("Vulkan", "GL", "Software"), lrps2.renderers.map { lrps2.step(it)!!.label })
        listOf("dolphin", "flycast", "ppsspp").forEach { assertTrue(it, TuningLadders.of(it)!!.renderers.isEmpty()) }
    }

    @Test
    fun `a press moves Automatic through each renderer and back`() {
        var at: String? = null
        val seen = List(4) { GameGraphics.next(lrps2, at).also { at = it } }
        assertEquals(listOf("vulkan", "gl", "software", null), seen)
        // A choice the ladder no longer has starts over.
        assertEquals("vulkan", GameGraphics.next(lrps2, "gone"))
    }

    @Test
    fun `graphics chosen for a game win over its own step and the catalog's, and Automatic leaves them`() {
        val chosen = TuningLadders.resolve(lrps2, "software", BundledTuning("software", null), "thor", chosen = "gl")
        assertEquals("gl", chosen.step.id)
        assertEquals(Source.CHOSEN, chosen.source)
        assertEquals(Source.OVERRIDE, TuningLadders.resolve(lrps2, "software", null, "thor", chosen = null).source)
        assertEquals(Source.DEFAULT, TuningLadders.resolve(lrps2, null, null, "thor", chosen = null).source)
        // Vulkan chosen is the Vulkan step, which still falls back to GL where Vulkan can't run.
        assertTrue(TuningLadders.resolve(lrps2, null, null, "thor", chosen = "vulkan").step.vulkan)
    }

    @Test
    fun `a choice that isn't one of the ladder's renderers counts as none`() {
        val dolphin = TuningLadders.of("dolphin")!!
        assertEquals(Source.DEFAULT, TuningLadders.resolve(dolphin, null, null, "thor", chosen = "efb-2x").source)
        assertEquals(Source.DEFAULT, TuningLadders.resolve(lrps2, null, null, "thor", chosen = "nonsense").source)
    }
}
