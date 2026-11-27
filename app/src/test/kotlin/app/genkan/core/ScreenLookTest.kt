package app.genkan.core

import com.swordfish.libretrodroid.ShaderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenLookTest {
    @Test
    fun `a game's own look wins over its console's`() {
        assertEquals(ScreenLook.CRT, ScreenLook.resolve(ScreenLook.CRT, ScreenLook.SHARP))
        assertEquals(ScreenLook.SHARP, ScreenLook.resolve(null, ScreenLook.SHARP))
    }

    @Test
    fun `each console starts with its own default`() {
        assertEquals(ScreenLook.SHARP, ScreenLook.defaultFor("psx"))
        assertEquals(ScreenLook.SHARP, ScreenLook.defaultFor("nds"))
        // The Famicom's pixel art, as the Super Famicom's and the PC Engine's.
        listOf("nes", "famicom", "fds", "genesis", "megadrive").forEach { assertEquals(it, ScreenLook.SHARP, ScreenLook.defaultFor(it)) }
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
        assertEquals(ShaderConfig.Default, ScreenLook.DEFAULT.shader(::asset))
        assertEquals(ShaderConfig.Sharp, ScreenLook.SHARP.shader(::asset))
        assertEquals(ShaderConfig.CRT, ScreenLook.CRT.shader(::asset))
        assertEquals(ShaderConfig.LCD, ScreenLook.LCD.shader(::asset))
        assertEquals(ShaderConfig.CUT(), ScreenLook.CUT.shader(::asset))
        assertEquals(ShaderConfig.CUT2(), ScreenLook.CUT2.shader(::asset))
        assertEquals(ShaderConfig.CUT3(), ScreenLook.CUT3.shader(::asset))
    }

    /** An asset's text, from the app's source tree (tests run in the app module). */
    private fun asset(path: String): String = java.io.File("src/main/assets/$path").readText()

    @Test
    fun `each RetroArch look's file is there and adapts, with its preset's filtering`() {
        val retroArch = ScreenLook.entries.filter { it.file != null }
        assertEquals(12, retroArch.size)
        for (look in retroArch) {
            val shader = look.shader(::asset) as ShaderConfig.Custom
            assertEquals(look.name, look.linear, shader.linear)
            assertTrue(look.name, shader.vertex.startsWith("#define VERTEX"))
            assertTrue(look.name, shader.fragment.startsWith("#define FRAGMENT"))
        }
    }
}
