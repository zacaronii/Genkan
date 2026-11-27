package app.genkan.core

import java.io.File
import java.util.zip.CRC32

/**
 * A Famicom (NES) cartridge's id for the Yomikata catalog: `FC-` and the CRC-32 of its PRG and CHR
 * ROM in capitals, as No-Intro counts it, without the 16-byte iNES or NES 2.0 header and the 512-byte
 * trainer the header may say is there (a file with no header is taken as PRG and CHR already); the
 * same CRC-32 in lower case is its build. Yomikata's rule (identify.py, identify_nes).
 */
object FamicomCartridge {

    private val INES = byteArrayOf(0x4E, 0x45, 0x53, 0x1A)
    private const val HEADER = 16
    private const val TRAINER = 512
    /** Past the largest Famicom ROMs: not a cartridge. */
    private const val MAX_SIZE = 16L * 1024 * 1024

    /** [file]'s id, or null when it can't be read or is too large to be a cartridge. */
    fun of(file: File): CartridgeId? = runCatching {
        if (file.length() > MAX_SIZE) return null
        of(file.readBytes())
    }.getOrNull()

    /** The id of a ROM file's bytes. */
    fun of(rom: ByteArray): CartridgeId? {
        val skip = if (rom.size >= HEADER && rom.copyOf(4).contentEquals(INES)) {
            HEADER + if ((rom[6].toInt() and 0x04) != 0) TRAINER else 0
        } else {
            0
        }
        if (rom.size <= skip) return null
        val crc = CRC32().apply { update(rom, skip, rom.size - skip) }.value
        return CartridgeId("FC-%08X".format(crc), "%08x".format(crc))
    }
}
