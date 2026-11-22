package app.genkan.core

import com.swordfish.libretrodroid.ShaderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenLookTest {
    @Test
    fun `a console steps through every look and back to Default`() {
        val seen = generateSequence(ScreenLook.DEFAULT) { it.next() }.take(ScreenLook.entries.size + 1).toList()
        assertEquals(ScreenLook.entries + ScreenLook.DEFAULT, seen)
    }

    @Test
    fun `a game steps from its console's look through every look and back to its console's`() {
        val seen = generateSequence(ScreenLook.nextOverride(null)) { ScreenLook.nextOverride(it) }.take(ScreenLook.entries.size).toList()
        assertEquals(ScreenLook.entries, seen)
        assertNull(ScreenLook.nextOverride(ScreenLook.entries.last()))
    }

    @Test
    fun `a game's own look wins over its console's`() {
        assertEquals(ScreenLook.CRT, ScreenLook.resolve(ScreenLook.CRT, ScreenLook.SHARP))
        assertEquals(ScreenLook.SHARP, ScreenLook.resolve(null, ScreenLook.SHARP))
    }

    @Test
    fun `each console starts with its own default`() {
        assertEquals(ScreenLook.SHARP, ScreenLook.defaultFor("psx"))
        assertEquals(ScreenLook.SHARP, ScreenLook.defaultFor("nds"))
        assertEquals(ScreenLook.LCD, ScreenLook.defaultFor("gb"))
        assertEquals(ScreenLook.LCD, ScreenLook.defaultFor("gbc"))
        assertEquals(ScreenLook.LCD, ScreenLook.defaultFor("gba"))
        assertEquals(ScreenLook.DEFAULT, ScreenLook.defaultFor("psp"))
        assertEquals(ScreenLook.DEFAULT, ScreenLook.defaultFor("3ds"))
    }

    @Test
    fun `no name or an unknown one is Default`() {
        assertEquals(ScreenLook.DEFAULT, ScreenLook.of(null))
        assertEquals(ScreenLook.DEFAULT, ScreenLook.of("GLOW"))
        assertEquals(ScreenLook.LCD, ScreenLook.of("LCD"))
    }

    @Test
    fun `each look is the player library's own shader`() {
        assertEquals(ShaderConfig.Default, ScreenLook.DEFAULT.shader)
        assertEquals(ShaderConfig.Sharp, ScreenLook.SHARP.shader)
        assertEquals(ShaderConfig.CRT, ScreenLook.CRT.shader)
        assertEquals(ShaderConfig.LCD, ScreenLook.LCD.shader)
        assertEquals(ShaderConfig.CUT(), ScreenLook.CUT.shader)
        assertEquals(ShaderConfig.CUT2(), ScreenLook.CUT2.shader)
        assertEquals(ShaderConfig.CUT3(), ScreenLook.CUT3.shader)
    }
}
