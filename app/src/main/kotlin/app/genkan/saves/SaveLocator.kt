package app.genkan.saves

import android.os.Environment
import app.genkan.launch.EmulatorCatalog
import java.io.File
import java.io.RandomAccessFile

/**
 * Where a game's save lives on the device: one file, or a folder that travels as a zip. [name] is
 * the file name it has in RomM; [emulator] is the RomM emulator folder it is filed under.
 */
data class SaveTarget(val emulator: String, val path: File, val folder: Boolean, val name: String)

/** Finds each emulator's save for a downloaded game. Unknown platforms have no target. */
class SaveLocator(private val storage: File = Environment.getExternalStorageDirectory()) {

    fun locate(platformSlug: String, rom: File): SaveTarget? = when {
        platformSlug == "3ds" -> azahar(rom)
        platformSlug in EmulatorCatalog.retroArchCores -> retroArch(platformSlug, rom)
        else -> null
    }

    /**
     * Azahar keeps a title's save under its emulated SD card as a folder, named by the title id
     * in the cartridge image's header.
     */
    private fun azahar(rom: File): SaveTarget? {
        val titleId = ThreeDs.titleId(rom) ?: return null
        val root = AZAHAR_ROOTS.map { File(storage, it) }.firstOrNull { File(it, "sdmc").isDirectory } ?: return null
        val high = titleId.substring(0, 8)
        val low = titleId.substring(8)
        val data = File(root, "sdmc/Nintendo 3DS/$ZERO_ID/$ZERO_ID/title/$high/$low/data")
        return SaveTarget("azahar", data, folder = true, name = "$titleId.zip")
    }

    /** RetroArch's battery save: saves/<core>/<rom name>.srm, as Genkan's RetroArch config sorts them. */
    private fun retroArch(platformSlug: String, rom: File): SaveTarget? {
        val core = EmulatorCatalog.retroArchCores[platformSlug] ?: return null
        val folder = File(storage, "RetroArch/saves/${RETROARCH_CORE_FOLDERS[core] ?: return null}")
        return SaveTarget(core, File(folder, rom.nameWithoutExtension + ".srm"), folder = false, name = rom.nameWithoutExtension + ".srm")
    }

    companion object {
        private const val ZERO_ID = "00000000000000000000000000000000"
        private val AZAHAR_ROOTS = listOf("Azahar", "azahar-emu", "citra-emu")
        /** Save sub-folders RetroArch names after each core's library name (sort_savefiles_enable). */
        val RETROARCH_CORE_FOLDERS = mapOf(
            "fceumm" to "FCEUmm",
            "snes9x" to "Snes9x",
            "mgba" to "mGBA",
            "gambatte" to "Gambatte",
            "mupen64plus_next_gles3" to "Mupen64Plus-Next",
        )
    }
}

/** Nintendo 3DS cartridge images (.3ds/.cci): an NCSD header whose media id is the title id. */
object ThreeDs {
    fun titleId(rom: File): String? = try {
        RandomAccessFile(rom, "r").use { file ->
            val header = ByteArray(0x110)
            file.readFully(header)
            titleId(header)
        }
    } catch (_: Exception) {
        null
    }

    fun titleId(header: ByteArray): String? {
        if (header.size < 0x110 || String(header, 0x100, 4, Charsets.US_ASCII) != "NCSD") return null
        var id = 0L
        for (i in 7 downTo 0) id = (id shl 8) or (header[0x108 + i].toLong() and 0xff)
        return "%016x".format(id)
    }
}
