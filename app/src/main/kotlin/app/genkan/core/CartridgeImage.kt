package app.genkan.core

import java.io.File

/**
 * A cartridge's image as Yomikata's harness gives a script at 0x01000000 on, for its build check
 * (docs/script-api.md there): a Game Boy's file whole, byte for byte; a Famicom's past its 16-byte
 * iNES header and the trainer it may say is there (the program, 8 KB banks, then the CHR; a file with
 * no header as it is); a Disk System disk past its fwNES header, side after side. Null for a console
 * without one, or a file too large to be one.
 */
object CartridgeImage {

    private val INES = byteArrayOf(0x4E, 0x45, 0x53, 0x1A)
    private val FWNES = byteArrayOf(0x46, 0x44, 0x53, 0x1A)
    private const val HEADER = 16
    private const val TRAINER = 512
    /** Past the largest of these (an 8 MB Game Boy Color cartridge): not one. */
    private const val MAX_SIZE = 16L * 1024 * 1024

    fun of(platform: String, file: File): ByteArray? = runCatching {
        if (platform !in PLATFORMS || file.length() > MAX_SIZE) return null
        of(platform, file.readBytes())
    }.getOrNull()

    fun of(platform: String, bytes: ByteArray): ByteArray? = when (platform) {
        "gb", "gbc" -> bytes
        "nes", "famicom" -> if (bytes.startsWith(INES)) {
            bytes.copyOfRange(minOf(bytes.size, HEADER + if ((bytes[6].toInt() and 0x04) != 0) TRAINER else 0), bytes.size)
        } else {
            bytes
        }
        "fds" -> if (bytes.startsWith(FWNES)) bytes.copyOfRange(minOf(bytes.size, HEADER), bytes.size) else bytes
        else -> null
    }?.takeIf { it.isNotEmpty() }

    private val PLATFORMS = setOf("gb", "gbc", "nes", "famicom", "fds")

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= HEADER && copyOf(prefix.size).contentEquals(prefix)
}
