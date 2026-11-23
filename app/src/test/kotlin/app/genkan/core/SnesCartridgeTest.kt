package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.CRC32

/** Made-up cartridges: a header written where a LoROM or HiROM game keeps it. */
class SnesCartridgeTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A 48-byte header (bus 0xFFB0-0xFFDF): [code] when given, a made-up title, [map], and a checksum pair that holds unless [badChecksum]. */
    private fun header(code: String?, map: Int, badChecksum: Boolean = false): ByteArray = ByteArray(0x30).also { h ->
        code?.toByteArray(Charsets.US_ASCII)?.copyInto(h, 2)
        "MADE UP GAME         ".toByteArray(Charsets.US_ASCII).copyInto(h, 0x10)
        h[0x25] = map.toByte()
        h[0x2A] = (if (code != null) 0x33 else 0x01).toByte()
        val checksum = 0x1234
        val complement = if (badChecksum) 0x1111 else checksum xor 0xFFFF
        h[0x2C] = complement.toByte(); h[0x2D] = (complement shr 8).toByte()
        h[0x2E] = checksum.toByte(); h[0x2F] = (checksum shr 8).toByte()
    }

    /** A [size]-byte file with [header] at the LoROM or HiROM place, after a copier header when [copier]. */
    private fun rom(header: ByteArray, hi: Boolean, size: Int = 0x20000, copier: Boolean = false): ByteArray {
        val skip = if (copier) 512 else 0
        return ByteArray(size + skip).also { header.copyInto(it, skip + if (hi) 0xFFB0 else 0x7FB0) }
    }

    private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value

    @Test
    fun `a LoROM game's code makes its id`() {
        val h = header("ZQ  ", 0x20)
        val id = SnesCartridge.of(rom(h, hi = false), 0x20000)!!
        assertEquals("SHVC-ZQ", id.id)
        assertEquals("%08x".format(crc(h)), id.headerCrc32)
    }

    @Test
    fun `a HiROM game is found at its own place`() {
        val h = header("ZQXJ", 0x21)
        assertEquals("SHVC-ZQXJ", SnesCartridge.of(rom(h, hi = true), 0x20000)!!.id)
    }

    @Test
    fun `a copier header in front is skipped`() {
        val lo = header("ZQ  ", 0x20)
        assertEquals("SHVC-ZQ", SnesCartridge.of(rom(lo, hi = false, copier = true), 0x20000 + 512)!!.id)
        val hi = header("ZQXJ", 0x31)
        val id = SnesCartridge.of(rom(hi, hi = true, copier = true), 0x20000 + 512)!!
        assertEquals("SHVC-ZQXJ", id.id)
        assertEquals("%08x".format(crc(hi)), id.headerCrc32)
    }

    @Test
    fun `without a game code the id is the header's CRC`() {
        val h = header(null, 0x20)
        val id = SnesCartridge.of(rom(h, hi = false), 0x20000)!!
        assertEquals("SFC-%08X".format(crc(h)), id.id)
        assertEquals("%08x".format(crc(h)), id.headerCrc32)
    }

    @Test
    fun `a header with a bad checksum is still found by its map mode`() {
        val h = header("ZQXJ", 0x21, badChecksum = true)
        assertEquals("SHVC-ZQXJ", SnesCartridge.of(rom(h, hi = true), 0x20000)!!.id)
    }

    @Test
    fun `the file is read from disk, and one too short for a header has none`() {
        val file = temp.newFile("game.sfc").apply { writeBytes(rom(header("ZQ  ", 0x20), hi = false)) }
        assertEquals("SHVC-ZQ", SnesCartridge.of(file)!!.id)
        assertNull(SnesCartridge.of(temp.newFile("tiny.sfc").apply { writeBytes(ByteArray(0x100)) }))
    }
}
