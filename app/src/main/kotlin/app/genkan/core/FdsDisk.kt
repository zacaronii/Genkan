package app.genkan.core

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * A Famicom Disk System disk's id for the Yomikata catalog, from the disk-info block that begins its
 * first side: `FDS-`, the maker's code as two hex digits and the game's four-character name code
 * (a character not A-Z or 0-9 as `-`, trailing spaces left out), and for the second disk of a set or
 * later `-D` and its number from 1. The build is the id and the revision as two hex digits; the
 * block's CRC-32 (its 56 bytes) picks the entry's version. The same with or without the 16-byte fwNES
 * header. Yomikata's rule (identify.py, identify_fds).
 */
object FdsDisk {

    private val FWNES = byteArrayOf(0x46, 0x44, 0x53, 0x1A)
    private val BLOCK1 = "\u0001*NINTENDO-HVC*".toByteArray(Charsets.ISO_8859_1)
    private const val HEADER = 16
    private const val BLOCK = 0x38
    private const val SIDE = 65_500L

    /** How many disk sides [file] holds (65,500 bytes each, past any fwNES header); 0 when it can't be read. */
    fun sides(file: File): Int = runCatching {
        val head = ByteArray(4)
        file.inputStream().use { it.read(head) }
        val body = file.length() - if (head.contentEquals(FWNES)) HEADER else 0
        (body / SIDE).toInt()
    }.getOrDefault(0)

    /** [file]'s id, or null when it isn't a Disk System image or can't be read. */
    fun of(file: File): CartridgeId? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val head = ByteArray(minOf(f.length(), (HEADER + BLOCK).toLong()).toInt())
            f.readFully(head)
            of(head)
        }
    }.getOrNull()

    /** The id from the start of a disk image (at least its first block, after any header). */
    fun of(head: ByteArray): CartridgeId? {
        val skip = if (head.size >= HEADER && head.copyOf(4).contentEquals(FWNES)) HEADER else 0
        if (head.size < skip + BLOCK) return null
        val block = head.copyOfRange(skip, skip + BLOCK)
        if (!block.copyOf(BLOCK1.size).contentEquals(BLOCK1)) return null
        val maker = block[0x0F].toInt() and 0xFF
        val code = String(block, 0x10, 4, Charsets.ISO_8859_1).trimEnd(' ')
            .map { if (it in 'A'..'Z' || it in '0'..'9') it else '-' }.joinToString("").ifEmpty { "----" }
        val number = block[0x17].toInt() and 0xFF
        val id = "FDS-%02X%s".format(maker, code) + if (number != 0) "-D${number + 1}" else ""
        val crc = CRC32().apply { update(block) }.value
        return CartridgeId(id, "%08x".format(crc), "%s-%02X".format(id, block[0x15].toInt() and 0xFF))
    }
}
