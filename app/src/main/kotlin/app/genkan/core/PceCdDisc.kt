package app.genkan.core

import com.swordfish.libretrodroid.GenkanTap
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * A PC Engine CD's id for the Yomikata catalog, from its disc: the data track's second sector, its
 * boot block (the system's name, the makers, the program's name), which holds "PC Engine CD-ROM
 * SYSTEM" at 0x20. The id is `PCECD-` and the block's CRC-32 in capitals; the same CRC-32 in lower
 * case is its build. Yomikata's rule (identify.py, identify_disc), there read through chdman.
 */
object PceCdDisc {

    private val SYSTEM = "PC Engine CD-ROM SYSTEM\u0000".toByteArray(Charsets.US_ASCII)
    private const val SYSTEM_AT = 0x20
    private const val BOOT_SECTOR = 1
    private const val USER_DATA = 2048

    /**
     * [file]'s id: a CHD (read through [chdSector], libchdr's), a cue sheet and its BIN files, or an
     * M3U of discs (its first); null for anything else, a disc that isn't a PC Engine CD, or one
     * that can't be read.
     */
    fun of(file: File, chdSector: (File, Int) -> ByteArray? = { f, n -> GenkanTap.chdDataSector(f.path, n) }): CartridgeId? = runCatching {
        val boot = when (file.extension.lowercase()) {
            "chd" -> chdSector(file, BOOT_SECTOR)
            "cue" -> cueSector(file, BOOT_SECTOR)
            "m3u" -> firstDisc(file)?.let { return@runCatching of(it, chdSector) }
            else -> null
        }
        boot?.let(::of)
    }.getOrNull()

    /** The id from a boot block; null when it isn't a PC Engine CD's. */
    fun of(boot: ByteArray): CartridgeId? {
        if (boot.size != USER_DATA) return null
        if (!boot.copyOfRange(SYSTEM_AT, SYSTEM_AT + SYSTEM.size).contentEquals(SYSTEM)) return null
        val crc = CRC32().apply { update(boot) }.value
        return CartridgeId("PCECD-%08X".format(crc), "%08x".format(crc))
    }

    /** An M3U's first disc, beside it unless the line gives a full path. */
    internal fun firstDisc(m3u: File): File? = m3u.readLines()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?.let { File(it).takeIf(File::isAbsolute) ?: File(m3u.parentFile, it) }

    /**
     * Sector [sector] of a cue sheet's first data track, from its INDEX 01, as user data: a track of
     * 2352-byte sectors keeps it after the sync and header (MODE1, 16 bytes) and the subheader too
     * (MODE2, 24); a 2048-byte one is all of it. Null when there's no data track or it can't be read.
     */
    internal fun cueSector(cue: File, sector: Int): ByteArray? {
        var bin: File? = null
        var mode: String? = null
        for (raw in cue.readLines()) {
            val line = raw.trim()
            when {
                line.startsWith("FILE ", ignoreCase = true) ->
                    bin = File(cue.parentFile, line.substringAfter(' ').substringBeforeLast(' ').trim().removeSurrounding("\""))
                line.startsWith("TRACK ", ignoreCase = true) -> mode = line.split(Regex("\\s+")).getOrNull(2)?.uppercase()
                line.startsWith("INDEX 01", ignoreCase = true) && mode != null && mode != "AUDIO" -> {
                    val (size, skip) = when (mode) {
                        "MODE1/2352" -> 2352 to 16
                        "MODE2/2352" -> 2352 to 24
                        "MODE1/2048", "MODE2/2048" -> 2048 to 0
                        else -> return null
                    }
                    val (m, s, f) = line.split(Regex("\\s+")).getOrNull(2)?.split(':')?.map { it.toInt() } ?: return null
                    val at = ((m * 60L + s) * 75 + f + sector) * size + skip
                    return RandomAccessFile(bin ?: return null, "r").use { r ->
                        if (at + USER_DATA > r.length()) return null
                        ByteArray(USER_DATA).also { r.seek(at); r.readFully(it) }
                    }
                }
            }
        }
        return null
    }
}
