package app.genkan.core

import java.io.File
import java.util.zip.CRC32

/**
 * A Mega Drive cartridge's id for the Yomikata catalog, from its header at 0x100 ("SEGA MEGA DRIVE"
 * or "SEGA GENESIS"): the serial of the product code at 0x180 ("GM T-12345 -00": the kind, the serial,
 * the revision at 0x18C), as the console's discs are named; a header without one gives `MD-` and the
 * CRC-32 of the whole ROM. The build is the id and the revision (just the id for an `MD-` one); the
 * ROM's CRC-32 picks the entry's version. An SMD dump is put back in order first. Yomikata's rule
 * (identify.py, identify_md).
 */
object MegaDriveCartridge {

    private const val SMD_HEADER = 0x200
    private const val SMD_BLOCK = 0x4000
    private val SEGA = "SEGA".toByteArray(Charsets.US_ASCII)
    private val SERIAL = Regex("[A-Z0-9][A-Z0-9-]{3,19}")
    /** Past the largest Mega Drive cartridges (with room for an SMD header): not one. */
    private const val MAX_SIZE = 16L * 1024 * 1024

    /** [file]'s id, or null when it isn't a Mega Drive cartridge or can't be read. */
    fun of(file: File): CartridgeId? = runCatching {
        if (file.length() > MAX_SIZE) return null
        of(file.readBytes())
    }.getOrNull()

    fun of(data: ByteArray): CartridgeId? {
        val rom = unpack(data)
        if (rom.size < 0x190 || !rom.copyOfRange(0x100, 0x104).contentEquals(SEGA)) return null
        val crc = CRC32().apply { update(rom) }.value
        val code = String(rom, 0x180, 14, Charsets.ISO_8859_1)
        val serial = code.substring(3, 12).trim().uppercase().replace(' ', '-').filter { it in 'A'..'Z' || it in '0'..'9' || it == '-' }.trim('-')
        val revision = code.substring(12, 14).uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.ifEmpty { "00" }
        if (SERIAL.matches(serial)) return CartridgeId(serial, "%08x".format(crc), "$serial-$revision")
        val id = "MD-%08X".format(crc)
        return CartridgeId(id, "%08x".format(crc), id)
    }

    /**
     * The ROM as the console sees it: an SMD dump (a 512-byte header, then 16 KB blocks, each the odd
     * bytes' 8 KB and then the even bytes') put back in order; any other image as it is.
     */
    fun unpack(data: ByteArray): ByteArray {
        val smd = data.size % SMD_BLOCK == SMD_HEADER &&
            !(data.size >= 0x304 && data.copyOfRange(0x300, 0x304).contentEquals(SEGA))
        if (!smd) return data
        val body = data.size - SMD_HEADER
        val out = ByteArray(body / SMD_BLOCK * SMD_BLOCK)
        for (block in 0 until body / SMD_BLOCK) {
            val at = SMD_HEADER + block * SMD_BLOCK
            for (i in 0 until SMD_BLOCK / 2) {
                out[block * SMD_BLOCK + 2 * i] = data[at + SMD_BLOCK / 2 + i]
                out[block * SMD_BLOCK + 2 * i + 1] = data[at + i]
            }
        }
        return out
    }
}
