package app.genkan.core

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * A Game Boy or Game Boy Color cartridge's id for the Yomikata catalog, from its header (0x100-0x14F):
 * a Color one sets bit 7 of 0x143. A later cartridge has a four-character game code at 0x13F-0x142
 * (its maker in 0x144, the old maker byte 0x33): the id is the code with the console's prefix (`CGB-`,
 * `DMG-`); else the header's CRC-32 with its own (`GBC-`, `GB-`). The build is that CRC-32 too.
 * Yomikata's rule (identify.py, identify_gb).
 */
object GameBoyCartridge {

    private const val HEADER_START = 0x100
    private const val HEADER_END = 0x150
    private val GAME_CODE = Regex("[A-Z0-9]{4}")

    /** [file]'s id, or null when it's too short to hold a header or can't be read. */
    fun of(file: File): CartridgeId? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            if (f.length() < HEADER_END) return null
            of(ByteArray(HEADER_END).also { f.readFully(it) })
        }
    }.getOrNull()

    /** The id from a cartridge's first 0x150 bytes. */
    fun of(head: ByteArray): CartridgeId? {
        if (head.size < HEADER_END) return null
        val crc = CRC32().apply { update(head, HEADER_START, HEADER_END - HEADER_START) }.value
        val color = (head[0x143].toInt() and 0x80) != 0
        val code = String(head, 0x13F, 4, Charsets.ISO_8859_1)
        val id = if ((head[0x14B].toInt() and 0xFF) == 0x33 && GAME_CODE.matches(code)) {
            (if (color) "CGB-" else "DMG-") + code
        } else {
            (if (color) "GBC-" else "GB-") + "%08X".format(crc)
        }
        return CartridgeId(id, "%08x".format(crc))
    }
}
