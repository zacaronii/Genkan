package app.genkan.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Made-up headers, laid out as a Saturn's system ID and a Dreamcast's IP.BIN are. */
class SegaHeaderTest {

    private fun header(system: String, fields: Map<Int, String>): ByteArray {
        val bytes = ByteArray(0x100) { ' '.code.toByte() }
        system.toByteArray().copyInto(bytes)
        fields.forEach { (at, text) -> text.toByteArray().copyInto(bytes, at) }
        return bytes
    }

    private fun saturn(product: String, version: String, device: String) =
        header("SEGA SEGASATURN ", mapOf(0x20 to product, 0x2A to version, 0x38 to device))

    @Test
    fun `a Saturn disc is its product number, its build that and the version`() {
        assertEquals(SegaHeader.Release("ABCD-00001", "ABCD-00001-V1.000"), SegaHeader.read(saturn("ABCD-00001", "V1.000", "CD-1/1")))
    }

    @Test
    fun `a disc of a set adds its number, since the set shares a product number`() {
        assertEquals(SegaHeader.Release("ABC-1234-D2", "ABC-1234-D2-V1.002"), SegaHeader.read(saturn("ABC-1234", "V1.002", "CD-2/3")))
        // One disc of one: no number.
        assertEquals("ABC-1234", SegaHeader.read(saturn("ABC-1234", "V1.002", "CD-1/1"))?.id)
    }

    @Test
    fun `a Dreamcast disc reads the same from its IP_BIN's own places`() {
        val dc = header("SEGA SEGAKATANA ", mapOf(0x20 to "GD-ROM3/3  ", 0x40 to "ABC-0001", 0x4A to "V1.001"))
        assertEquals(SegaHeader.Release("ABC-0001-D3", "ABC-0001-D3-V1.001"), SegaHeader.read(dc))
        val single = header("SEGA SEGAKATANA ", mapOf(0x20 to "GD-ROM1/1  ", 0x40 to "ABC-0001", 0x4A to "V1.000"))
        assertEquals("ABC-0001", SegaHeader.read(single)?.id)
    }

    @Test
    fun `a header can sit anywhere in RAM`() {
        val ram = ByteArray(0x3000)
        saturn("ABCD-00001", "V1.000", "CD-1/1").copyInto(ram, 0x2000)
        assertEquals("ABCD-00001", SegaHeader.read(ram, 0x2000)?.id)
        assertNull(SegaHeader.read(ram, 0x2F00))
    }

    @Test
    fun `no id without the system's name or with a product number that isn't one`() {
        assertNull(SegaHeader.read(ByteArray(0x100)))
        assertNull(SegaHeader.read(header("SEGA SEGASATURX ", mapOf(0x20 to "ABCD-00001"))))
        assertNull(SegaHeader.read(saturn("abcd-00001", "V1.000", "CD-1/1")))
        assertNull(SegaHeader.read(saturn("AB", "V1.000", "CD-1/1")))
        assertNull(SegaHeader.read(saturn("-ABCD", "V1.000", "CD-1/1")))
        assertNull(SegaHeader.read(saturn("ABCD-00001", "V1.000", "CD-1/1").copyOf(0x40)))
    }
}
