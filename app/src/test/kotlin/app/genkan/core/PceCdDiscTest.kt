package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32

class PceCdDiscTest {
    @get:Rule val folder = TemporaryFolder()

    /** A made-up boot block: the system's name at 0x20, and a program name of our own. */
    private val boot = ByteArray(2048).also {
        "PC Engine CD-ROM SYSTEM\u0000".toByteArray().copyInto(it, 0x20)
        "TEST PROGRAM".toByteArray().copyInto(it, 0x6A)
    }
    private val crc = CRC32().apply { update(boot) }.value
    private val expected = CartridgeId("PCECD-%08X".format(crc), "%08x".format(crc))

    /** A raw 2352-byte MODE1 sector holding [user]: 16 bytes of sync and header before it. */
    private fun mode1(user: ByteArray) = ByteArray(16) + user + ByteArray(2352 - 16 - user.size)

    @Test
    fun `the id is PCECD- and the boot block's CRC-32, which is also its build`() {
        assertEquals(expected, PceCdDisc.of(boot))
        assertEquals("%08X".format(crc), expected.id.removePrefix("PCECD-"))
        // Not a PC Engine CD's boot block, or not a whole sector.
        assertNull(PceCdDisc.of(ByteArray(2048)))
        assertNull(PceCdDisc.of(boot.copyOf(2047)))
    }

    @Test
    fun `a cue sheet with a BIN per track, the data track's sector 1 from its INDEX 01`() {
        File(folder.root, "Track 01.bin").writeBytes(ByteArray(2352 * 3))
        File(folder.root, "Track 02.bin").writeBytes(mode1(ByteArray(2048) { 0x11 }) + mode1(boot) + mode1(ByteArray(2048)))
        val cue = File(folder.root, "game.cue").apply {
            writeText(
                """
                FILE "Track 01.bin" BINARY
                  TRACK 01 AUDIO
                    INDEX 01 00:00:00
                FILE "Track 02.bin" BINARY
                  TRACK 02 MODE1/2352
                    INDEX 01 00:00:00
                """.trimIndent(),
            )
        }
        assertEquals(expected, PceCdDisc.of(cue) { _, _ -> error("not a CHD") })
    }

    @Test
    fun `a cue sheet with one BIN, the data track starts at its INDEX 01 in the file`() {
        val audio = ByteArray(2352 * 4) { 0x55 }
        File(folder.root, "game.bin").writeBytes(audio + mode1(ByteArray(2048)) + mode1(boot))
        val cue = File(folder.root, "game.cue").apply {
            writeText("FILE \"game.bin\" BINARY\n  TRACK 01 AUDIO\n    INDEX 01 00:00:00\n  TRACK 02 MODE1/2352\n    INDEX 01 00:00:04\n")
        }
        assertEquals(expected, PceCdDisc.of(cue))
        // A sheet with no data track, or whose BIN is too short, gives none.
        cue.writeText("FILE \"game.bin\" BINARY\n  TRACK 01 AUDIO\n    INDEX 01 00:00:00\n")
        assertNull(PceCdDisc.of(cue))
        cue.writeText("FILE \"game.bin\" BINARY\n  TRACK 01 MODE1/2352\n    INDEX 01 00:00:05\n")
        assertNull(PceCdDisc.of(cue))
    }

    @Test
    fun `a CHD is read through libchdr, and an M3U by its first disc`() {
        val chd = File(folder.root, "disc 1.chd").apply { writeBytes(byteArrayOf(1)) }
        val asked = mutableListOf<Pair<String, Int>>()
        val reader = { f: File, n: Int -> asked += f.name to n; boot }
        assertEquals(expected, PceCdDisc.of(chd, reader))
        val m3u = File(folder.root, "game.m3u").apply { writeText("# discs\n\ndisc 1.chd\ndisc 2.chd\n") }
        assertEquals(expected, PceCdDisc.of(m3u, reader))
        assertEquals(listOf("disc 1.chd" to 1, "disc 1.chd" to 1), asked)
        // A CHD that reads as nothing, or a file of another kind, has no id.
        assertNull(PceCdDisc.of(chd) { _, _ -> null })
        assertNull(PceCdDisc.of(File(folder.root, "game.iso")) { _, _ -> boot })
    }

    @Test
    fun `a PC Engine CD's id is read by CartridgeId as a cartridge's is`() {
        File(folder.root, "game.bin").writeBytes(mode1(ByteArray(2048)) + mode1(boot))
        val cue = File(folder.root, "game.cue").apply { writeText("FILE \"game.bin\" BINARY\n  TRACK 01 MODE1/2352\n    INDEX 01 00:00:00\n") }
        assertEquals(expected, CartridgeId.of("turbografx-cd", cue))
        assertNull(CartridgeId.of("tg16", cue))
    }
}
