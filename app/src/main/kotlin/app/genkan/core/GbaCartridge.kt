package app.genkan.core

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * A Game Boy Advance cartridge's id for the Yomikata catalog, from the header at the start of its
 * file: `AGB-` and the 4-letter game code at 0xAC. Its build is the CRC-32 of the header's 192
 * bytes (0x00-0xBF). Yomikata's rule (docs/format.md, "Game Boy Advance").
 */
object GbaCartridge {

    /** The header: the entry point, the logo, the title, the code and the rest, to 0xBF. */
    const val HEADER = 0xC0
    private const val CODE = 0xAC
    /** A fixed byte every cartridge's header has. */
    private const val FIXED = 0xB2

    /** [file]'s id, or null when it's too short to hold a header, has none, or can't be read. */
    fun of(file: File): CartridgeId? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            if (f.length() < HEADER) return@use null
            val head = ByteArray(HEADER)
            f.readFully(head)
            of(head)
        }
    }.getOrNull()

    /** The id from a file's first bytes ([head], at least the header's 192). */
    fun of(head: ByteArray): CartridgeId? {
        if (head.size < HEADER || (head[FIXED].toInt() and 0xFF) != 0x96) return null
        val code = String(head, CODE, 4, Charsets.ISO_8859_1)
        if (!GAME_CODE.matches(code)) return null
        val crc = CRC32().apply { update(head, 0, HEADER) }.value
        return CartridgeId("AGB-$code", "%08x".format(crc))
    }

    private val GAME_CODE = Regex("[A-Z0-9]{4}")
}
