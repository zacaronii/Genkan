package app.genkan.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The PS1 release id, read from the boot path the BIOS leaves in RAM. */
class Ps1SerialTest {
    private fun ram(text: String) = ByteArray(64) + text.toByteArray(Charsets.ISO_8859_1) + ByteArray(64)

    @Test
    fun `the boot path with one or two backslashes`() {
        assertEquals("SLPS-01001", TextSamples.ps1Serial(ram("cdrom:\\SLPS_010.01;1")))
        assertEquals("SCPS-10088", TextSamples.ps1Serial(ram("cdrom:\\\\SCPS_100.88;1")))
    }

    @Test
    fun `the boot path with none, as Tales of Destiny's`() {
        assertEquals("SLPS-01100", TextSamples.ps1Serial(ram("cdrom:SLPS_011.00;1")))
    }

    @Test
    fun `no boot path, no id`() {
        assertNull(TextSamples.ps1Serial(ram("SLPS_011.00 without its device")))
    }
}
