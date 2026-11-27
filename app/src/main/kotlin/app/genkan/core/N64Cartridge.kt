package app.genkan.core

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * An N64 cartridge's id for the Yomikata catalog, from the header (its first 0x40 bytes) in the
 * cartridge's own byte order, whichever order the dump keeps (.z64 as it is, .v64 with each 16-bit
 * word's bytes swapped, .n64 with each 32-bit word's reversed): `NUS-` and the four-character game
 * code at 0x3B, else `N64-` and the header's CRC-32. The build is the id, `-` and the version at
 * 0x3F as two hex digits; the header's CRC-32 picks the entry's version (`header_crc32`). Yomikata's
 * rule (identify.py, identify_n64).
 */
object N64Cartridge {

    private const val HEADER = 0x40
    private val FIRST = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40)
    private val GAME_CODE = Regex("[A-Z0-9]{4}")

    /** [file]'s id, or null when it isn't an N64 cartridge or can't be read. */
    fun of(file: File): CartridgeId? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            if (f.length() < HEADER) return null
            of(ByteArray(HEADER).also { f.readFully(it) })
        }
    }.getOrNull()

    /** The id from a header as the dump keeps it; null when its first word isn't a cartridge's. */
    fun of(head: ByteArray): CartridgeId? {
        val header = bigEndian(head.copyOf(HEADER)) ?: return null
        val crc = CRC32().apply { update(header) }.value
        val code = String(header, 0x3B, 4, Charsets.ISO_8859_1)
        val id = if (GAME_CODE.matches(code)) "NUS-$code" else "N64-%08X".format(crc)
        return CartridgeId(id, "%08x".format(crc), "%s-%02X".format(id, header[0x3F].toInt() and 0xFF))
    }

    /** [data] in the cartridge's own order (big-endian), from any of the three; null when it's none. */
    internal fun bigEndian(data: ByteArray): ByteArray? {
        val n = data.size / 4 * 4
        val word = (0 until 4).map { data[it] }
        return when {
            word == FIRST.toList() -> data.copyOf(n)
            word == listOf(FIRST[1], FIRST[0], FIRST[3], FIRST[2]) -> ByteArray(n) { data[it xor 1] }
            word == FIRST.reversed() -> ByteArray(n) { data[it xor 3] }
            else -> null
        }
    }
}
