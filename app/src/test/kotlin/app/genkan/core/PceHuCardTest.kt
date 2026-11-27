package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PceHuCardTest {
    @get:Rule val folder = TemporaryFolder()

    /** A made-up 32 KB ROM: four 8 KB banks of a pattern. */
    private val rom = ByteArray(4 * 8192) { ((it * 31 + 7) and 0xFF).toByte() }

    // The ids below are what Yomikata's identify.py (identify_hucard) gives for the same bytes.

    @Test
    fun `a HuCard is PCE- and its ROM's CRC-32, which is also its build`() {
        assertEquals(CartridgeId("PCE-7BC368D8", "7bc368d8"), PceHuCard.of(rom))
    }

    @Test
    fun `a copier's 512-byte header is left out, and only when the size says it's there`() {
        val headed = ByteArray(512) { 0xAA.toByte() } + rom
        assertEquals(CartridgeId("PCE-7BC368D8", "7bc368d8"), PceHuCard.of(headed))
        // 100 bytes more isn't a header's size: all of it counts.
        assertEquals(CartridgeId("PCE-F043D836", "f043d836"), PceHuCard.of(rom + ByteArray(100)))
        assertNull(PceHuCard.of(ByteArray(0)))
    }

    @Test
    fun `a HuCard's file is read by CartridgeId as other cartridges are`() {
        val file = File(folder.root, "game.pce").apply { writeBytes(rom) }
        assertEquals(CartridgeId("PCE-7BC368D8", "7bc368d8"), CartridgeId.of("tg16", file))
        assertNull(PceHuCard.of(File(folder.root, "missing.pce")))
    }
}
