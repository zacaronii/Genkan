package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FamicomTest {
    @get:Rule val folder = TemporaryFolder()

    /** A made-up 24 KB cartridge: 16 KB of PRG and 8 KB of CHR. */
    private val prgChr = ByteArray(24 * 1024) { ((it * 13 + 5) and 0xFF).toByte() }
    private fun ines(flags6: Int) = byteArrayOf(0x4E, 0x45, 0x53, 0x1A, 1, 1, flags6.toByte()) + ByteArray(9)

    /** A made-up disk side: its info block (maker 0x01, name code TST, revision 2, disk 1) and filler. */
    private fun side(name: String = "TST ", number: Int = 0) = ByteArray(65500).also {
        "\u0001*NINTENDO-HVC*".toByteArray(Charsets.ISO_8859_1).copyInto(it, 0)
        it[0x0F] = 0x01
        name.toByteArray(Charsets.ISO_8859_1).copyInto(it, 0x10)
        it[0x15] = 2
        it[0x17] = number.toByte()
    }
    private val fwnes = byteArrayOf(0x46, 0x44, 0x53, 0x1A, 1) + ByteArray(11)

    // The ids below are what Yomikata's identify.py (identify_nes, identify_fds) gives for the same bytes.

    @Test
    fun `a cartridge is FC- and its PRG and CHR's CRC-32, with or without its header and trainer`() {
        val expected = CartridgeId("FC-5F960C81", "5f960c81")
        assertEquals(expected, FamicomCartridge.of(prgChr))
        assertEquals(expected, FamicomCartridge.of(ines(0) + prgChr))
        assertEquals(expected, FamicomCartridge.of(ines(0x04) + ByteArray(512) { 0x77 } + prgChr))
        assertNull(FamicomCartridge.of(ines(0)))
    }

    @Test
    fun `a disk is FDS-, its maker and name code, its build with the revision, alike with an fwNES header`() {
        val expected = CartridgeId("FDS-01TST", "d760ca15", "FDS-01TST-02")
        assertEquals(expected, FdsDisk.of(side()))
        assertEquals(expected, FdsDisk.of(fwnes + side()))
        // A later disk of a set; a name code's odd characters as "-".
        assertEquals("FDS-01TST-D2", FdsDisk.of(side(number = 1))!!.id)
        assertEquals("FDS-01T-T", FdsDisk.of(side(name = "T.T "))!!.id)
        assertNull(FdsDisk.of(ByteArray(65500)))
    }

    @Test
    fun `read from their files by CartridgeId`() {
        val nes = File(folder.root, "game.nes").apply { writeBytes(ines(0) + prgChr) }
        val fds = File(folder.root, "game.fds").apply { writeBytes(fwnes + side() + side()) }
        assertEquals("FC-5F960C81", CartridgeId.of("nes", nes)!!.id)
        assertEquals("FC-5F960C81", CartridgeId.of("famicom", nes)!!.id)
        assertEquals(CartridgeId("FDS-01TST", "d760ca15", "FDS-01TST-02"), CartridgeId.of("fds", fds))
        assertEquals("FDS-01TST-02", ScriptEvents.buildId("fds", null, CartridgeId.of("fds", fds)))
        // Its sides, for a swap: two, with or without the fwNES header.
        assertEquals(2, FdsDisk.sides(fds))
        assertEquals(2, FdsDisk.sides(File(folder.root, "bare.fds").apply { writeBytes(side() + side()) }))
        assertEquals(0, FdsDisk.sides(File(folder.root, "missing.fds")))
        assertEquals("5f960c81", ScriptEvents.buildId("nes", null, CartridgeId.of("nes", nes)))
    }
}
