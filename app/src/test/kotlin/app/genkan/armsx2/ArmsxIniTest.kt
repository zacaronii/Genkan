package app.genkan.armsx2

import org.junit.Assert.assertEquals
import org.junit.Test

class ArmsxIniTest {

    private val settings = """
        [UI]
        Theme = Dark

        [EmuCore]
        EnableCheats = false
        EnablePINE = false
        PINESlot = 28012

        [EmuCore/GS]
        Renderer = -1
    """.trimIndent()

    @Test
    fun `PINE is switched on in place, and back to what it was`() {
        val before = ArmsxIni.pineValue(settings)
        assertEquals("false", before)
        val on = ArmsxIni.withPine(settings)
        assertEquals("true", ArmsxIni.pineValue(on))
        // Nothing else in the file moves.
        assertEquals(settings.replace("EnablePINE = false", "EnablePINE = true"), on)
        assertEquals(settings, ArmsxIni.restored(on, before))
    }

    @Test
    fun `a file without the switch gets it, and ARMSX2's default back`() {
        val bare = "[EmuCore]\nEnableCheats = false\n"
        assertEquals("", ArmsxIni.pineValue(bare))
        val on = ArmsxIni.withPine(bare)
        assertEquals("true", ArmsxIni.pineValue(on))
        assertEquals("false", ArmsxIni.pineValue(ArmsxIni.restored(on, "")))
    }

    @Test
    fun `the port is the file's slot, else ARMSX2's default`() {
        assertEquals(28012, ArmsxIni.port(settings))
        assertEquals(Pine.PORT, ArmsxIni.port("[EmuCore]\nEnablePINE = true\n"))
        assertEquals(Pine.PORT, ArmsxIni.port("[EmuCore]\nPINESlot = 99999\n"))
    }
}
