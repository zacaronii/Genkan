package app.genkan.core

import java.io.File
import java.util.zip.CRC32

/**
 * A PC Engine HuCard's id for the Yomikata catalog: `PCE-` and the CRC-32 of the ROM in capitals,
 * without the 512-byte copier header some dumps have (a size that leaves 512 when divided by 8192);
 * the same CRC-32 in lower case is its build. Yomikata's rule (identify.py, identify_hucard).
 */
object PceHuCard {

    private const val COPIER = 512
    private const val BANK = 8192
    /** The largest HuCard (a Street Fighter II's 2.5 MB) with room to spare: past it, not a HuCard. */
    private const val MAX_SIZE = 8L * 1024 * 1024

    /** [file]'s id, or null when it can't be read or is too large to be a HuCard. */
    fun of(file: File): CartridgeId? = runCatching {
        if (file.length() > MAX_SIZE) return null
        of(file.readBytes())
    }.getOrNull()

    /** The id of a ROM's bytes. */
    fun of(rom: ByteArray): CartridgeId? {
        if (rom.isEmpty()) return null
        val skip = if (rom.size % BANK == COPIER) COPIER else 0
        val crc = CRC32().apply { update(rom, skip, rom.size - skip) }.value
        return CartridgeId("PCE-%08X".format(crc), "%08x".format(crc))
    }
}
