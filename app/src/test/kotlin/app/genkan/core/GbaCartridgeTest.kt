package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32

/** Made-up cartridges: a GBA header at the start of the file. */
class GbaCartridgeTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A [size]-byte file: a made-up title, the game [code] at 0xAC, the fixed 0x96 at 0xB2 unless [noFixed]. */
    private fun rom(code: String, size: Int = 0x1000, noFixed: Boolean = false): ByteArray = ByteArray(size).also { r ->
        for (i in 0 until 0xA0) r[i] = (i * 7).toByte()
        "MADE UP GAME".toByteArray(Charsets.US_ASCII).copyInto(r, 0xA0)
        code.toByteArray(Charsets.US_ASCII).copyInto(r, 0xAC)
        "01".toByteArray(Charsets.US_ASCII).copyInto(r, 0xB0)
        if (!noFixed) r[0xB2] = 0x96.toByte()
        r[0xBD] = 0x5A
        // Past the header: not part of the build.
        r[0xC0] = 0x11
    }

    private fun headerCrc(rom: ByteArray) = "%08x".format(CRC32().apply { update(rom, 0, 0xC0) }.value)

    @Test
    fun `the game code makes the id, the header's 192 bytes the build`() {
        val r = rom("ZQXJ")
        val id = GbaCartridge.of(r)!!
        assertEquals("AGB-ZQXJ", id.id)
        assertEquals(headerCrc(r), id.headerCrc32)
        assertEquals(8, id.headerCrc32.length)
        assertEquals(id.headerCrc32.lowercase(), id.headerCrc32)
    }

    @Test
    fun `bytes past the header don't change the build`() {
        val a = rom("ZQXJ")
        val b = rom("ZQXJ").also { it[0xC0] = 0x22; it[0xFFF] = 0x33 }
        assertEquals(GbaCartridge.of(a), GbaCartridge.of(b))
        val c = rom("ZQXJ").also { it[0xBF] = 0x44 }
        assertEquals("AGB-ZQXJ", GbaCartridge.of(c)!!.id)
        assert(GbaCartridge.of(c)!!.headerCrc32 != GbaCartridge.of(a)!!.headerCrc32)
    }

    @Test
    fun `no header, no id`() {
        assertNull(GbaCartridge.of(rom("ZQXJ", noFixed = true)))
        assertNull(GbaCartridge.of(rom("zq j")))
        assertNull(GbaCartridge.of(rom("ZQXJ").copyOf(0xBF)))
    }

    @Test
    fun `read from the file, by the game's platform`() {
        val r = rom("ZQXJ")
        val file = File(temp.root, "game.gba").apply { writeBytes(r) }
        assertEquals(CartridgeId("AGB-ZQXJ", headerCrc(r)), GbaCartridge.of(file))
        assertEquals(GbaCartridge.of(file), CartridgeId.of("gba", file))
        // The platform picks the rule: a console whose games aren't read so gives none.
        assertNull(CartridgeId.of("psx", file))
        assertNull(GbaCartridge.of(File(temp.root, "short.gba").apply { writeBytes(ByteArray(0x40)) }))
        assertNull(GbaCartridge.of(File(temp.root, "missing.gba")))
    }
}
