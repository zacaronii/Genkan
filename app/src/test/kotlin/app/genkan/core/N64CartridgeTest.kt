package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class N64CartridgeTest {
    @get:Rule val folder = TemporaryFolder()

    /** A made-up header in the cartridge's own order (.z64): game code N9ZJ, version 1. */
    private val z64 = ByteArray(0x40).also {
        byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40).copyInto(it, 0)
        "TEST GAME           ".toByteArray().copyInto(it, 0x20)
        "N9ZJ".toByteArray().copyInto(it, 0x3B)
        it[0x3F] = 1
    }

    // The ids below are what Yomikata's identify.py (identify_n64) gives for the same bytes.
    private val expected = CartridgeId("NUS-N9ZJ", "83d1150c", "NUS-N9ZJ-01")

    @Test
    fun `an N64 cartridge is NUS- and its game code, its build the id and version`() {
        assertEquals(expected, N64Cartridge.of(z64))
    }

    @Test
    fun `the same from a v64 (16-bit words swapped) and an n64 (32-bit words reversed)`() {
        assertEquals(expected, N64Cartridge.of(ByteArray(0x40) { z64[it xor 1] }))
        assertEquals(expected, N64Cartridge.of(ByteArray(0x40) { z64[it xor 3] }))
    }

    @Test
    fun `without a game code it is N64- and the header's CRC-32, and nothing that isn't a cartridge`() {
        val noCode = z64.copyOf().also { ByteArray(4).copyInto(it, 0x3B) }
        assertEquals(CartridgeId("N64-828C48E6", "828c48e6", "N64-828C48E6-01"), N64Cartridge.of(noCode))
        assertNull(N64Cartridge.of(ByteArray(0x40)))
    }

    @Test
    fun `read from the file, and handed from the player's process with its build`() {
        val file = File(folder.root, "game.z64").apply { writeBytes(z64 + ByteArray(0x1000)) }
        assertEquals(expected, CartridgeId.of("n64", file))
        assertEquals(expected, CartridgeId.parse(expected.wire()))
        assertEquals(CartridgeId("SHVC-ZQ", "1a2b3c4d"), CartridgeId.parse("SHVC-ZQ 1a2b3c4d"))
        assertNull(CartridgeId.parse("one"))
        // Scripts name an N64 build by the id and version; other cartridges by the CRC.
        assertEquals("NUS-N9ZJ-01", ScriptEvents.buildId("n64", null, expected))
        assertEquals("1a2b3c4d", ScriptEvents.buildId("snes", null, CartridgeId("SHVC-ZQ", "1A2B3C4D")))
        assertNull(N64Cartridge.of(File(folder.root, "missing.z64")))
    }
}
