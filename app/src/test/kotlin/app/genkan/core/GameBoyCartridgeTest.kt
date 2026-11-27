package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameBoyCartridgeTest {
    @get:Rule val folder = TemporaryFolder()

    /** A made-up cartridge start: a title, a game code of our own (Q9ZJ), the Color byte, the old maker byte. */
    private fun head(code: String = "Q9ZJ", color: Int = 0x80, oldMaker: Int = 0x33) = ByteArray(0x150) { ((it * 11 + 1) and 0xFF).toByte() }.also {
        "TESTGAME\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(it, 0x134)
        code.toByteArray(Charsets.ISO_8859_1).copyInto(it, 0x13F)
        it[0x143] = color.toByte()
        it[0x14B] = oldMaker.toByte()
    }

    // The ids below are what Yomikata's identify.py (identify_gb) gives for the same bytes.

    @Test
    fun `a later cartridge is its game code, CGB- for a Color one and DMG- for one that isn't`() {
        assertEquals(CartridgeId("CGB-Q9ZJ", "0e417fd2"), GameBoyCartridge.of(head()))
        assertEquals(CartridgeId("DMG-Q9ZJ", "ae2b5ac5"), GameBoyCartridge.of(head(color = 0x00)))
    }

    @Test
    fun `an older one is its header's CRC-32, GBC- or GB-`() {
        assertEquals(CartridgeId("GBC-D5A09434", "d5a09434"), GameBoyCartridge.of(head(oldMaker = 0x01)))
        assertEquals(CartridgeId("GB-75CAB123", "75cab123"), GameBoyCartridge.of(head(color = 0x00, oldMaker = 0x01)))
        assertNull(GameBoyCartridge.of(ByteArray(0x14F)))
    }

    @Test
    fun `read from the file for either platform, its build the CRC`() {
        val file = File(folder.root, "game.gbc").apply { writeBytes(head() + ByteArray(0x8000 - 0x150)) }
        assertEquals("CGB-Q9ZJ", CartridgeId.of("gbc", file)!!.id)
        assertEquals("CGB-Q9ZJ", CartridgeId.of("gb", file)!!.id)
        assertEquals("0e417fd2", ScriptEvents.buildId("gbc", null, CartridgeId.of("gbc", file)))
        assertNull(GameBoyCartridge.of(File(folder.root, "missing.gb")))
    }
}
