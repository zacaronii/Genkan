package app.genkan.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MegaDriveTest {
    @get:Rule val folder = TemporaryFolder()

    /** A made-up 64 KB cartridge: the SEGA header and a product code of our own, T-99901 revision 02. */
    private val rom = ByteArray(64 * 1024) { ((it * 29 + 3) and 0xFF).toByte() }.also {
        "SEGA MEGA DRIVE ".toByteArray().copyInto(it, 0x100)
        "GM T-99901 -02".toByteArray().copyInto(it, 0x180)
    }

    /** The same as an SMD dump: a 512-byte header, then each 16 KB block's odd bytes and then its even ones. */
    private val smd = ByteArray(0x200) + (0 until rom.size / 0x4000).flatMap { block ->
        val at = block * 0x4000
        (0 until 0x2000).map { rom[at + 2 * it + 1] } + (0 until 0x2000).map { rom[at + 2 * it] }
    }.toByteArray()

    // The ids below are what Yomikata's identify.py (identify_md) gives for the same bytes.
    private val expected = CartridgeId("T-99901", "62e40037", "T-99901-02")

    @Test
    fun `a cartridge is its product code's serial, its build the serial and revision`() {
        assertEquals(expected, MegaDriveCartridge.of(rom))
    }

    @Test
    fun `an SMD dump is put back in order first`() {
        assertArrayEquals(rom, MegaDriveCartridge.unpack(smd))
        assertEquals(expected, MegaDriveCartridge.of(smd))
        assertArrayEquals(rom, MegaDriveCartridge.unpack(rom))
    }

    @Test
    fun `without a serial it is MD- and the ROM's CRC-32, and nothing without the SEGA header`() {
        val noCode = rom.copyOf().also { "              ".toByteArray().copyInto(it, 0x180) }
        assertEquals(CartridgeId("MD-0090BB80", "0090bb80", "MD-0090BB80"), MegaDriveCartridge.of(noCode))
        assertNull(MegaDriveCartridge.of(ByteArray(64 * 1024)))
    }

    @Test
    fun `read from the file, and its image for scripts as the console sees it`() {
        val file = File(folder.root, "game.md").apply { writeBytes(smd) }
        assertEquals(expected, CartridgeId.of("genesis", file))
        assertEquals("T-99901-02", ScriptEvents.buildId("genesis", null, CartridgeId.of("genesis", file)))
        assertArrayEquals(rom, CartridgeImage.of("genesis", file))
        assertArrayEquals(rom, CartridgeImage.of("megadrive", rom))
    }
}
