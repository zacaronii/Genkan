package app.genkan.core

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * A Super Famicom cartridge's id for the Yomikata catalog, from the internal header in its file:
 * `SHVC-` and the game code where the header has one (an extended header, maker 0x33), else
 * `SFC-` and the header's CRC-32. The same CRC-32 picks the entry's version (`header_crc32`).
 * Yomikata's rule (docs/format.md, "Super Famicom"), checked against its identify vectors.
 */
object SnesCartridge {

    /** The header's bus addresses 0xFFB0-0xFFDF, as 48 bytes. */
    private const val HEADER = 0x30
    private const val LOROM = 0x7FB0
    private const val HIROM = 0xFFB0
    private const val COPIER = 512

    /** [file]'s id, or null when it's too short to hold a header or can't be read. */
    fun of(file: File): CartridgeId? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val size = f.length()
            val head = ByteArray(minOf(size, (COPIER + HIROM + HEADER).toLong()).toInt())
            f.readFully(head)
            of(head, size)
        }
    }.getOrNull()

    /** The id from the start of a file of [size] bytes ([head]: its first bytes, at least up to the header). */
    fun of(head: ByteArray, size: Long): CartridgeId? {
        // A copier's 512-byte header in front of the game.
        val skip = if (size % 0x8000 == COPIER.toLong()) COPIER else 0
        val header = listOf(LOROM to false, HIROM to true)
            .mapNotNull { (at, hi) -> head.slice(skip + at, HEADER)?.let { it to score(it, hi) } }
            // The better score; LoROM on a tie, as it comes first.
            .maxByOrNull { it.second }?.first ?: return null
        val crc = CRC32().apply { update(header) }.value
        val code = String(header, 2, 4, Charsets.ISO_8859_1).trimEnd(' ')
        val id = if ((header[0x2A].toInt() and 0xFF) == 0x33 && GAME_CODE.matches(code)) {
            "SHVC-$code"
        } else {
            "SFC-%08X".format(crc)
        }
        return CartridgeId(id, "%08x".format(crc))
    }

    /** A game code: 2 to 4 letters and digits, once trailing spaces are dropped. */
    private val GAME_CODE = Regex("[A-Z0-9]{2,4}")

    /** How much [header] looks like the real one, for a HiROM ([hi]) or LoROM game. */
    private fun score(header: ByteArray, hi: Boolean): Int {
        var score = 0
        if (u16(header, 0x2C) xor u16(header, 0x2E) == 0xFFFF) score += 2
        // The map mode's bit 0: 0 on LoROM, 1 on HiROM.
        if ((header[0x25].toInt() and 0x01 == 1) == hi) score += 1
        return score
    }

    private fun u16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.slice(at: Int, length: Int): ByteArray? = if (at + length <= size) copyOfRange(at, at + length) else null
}
