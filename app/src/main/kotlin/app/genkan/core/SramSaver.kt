package app.genkan.core

import android.util.Log
import java.io.File

/**
 * Writes a game's battery save to [file] when it changed since the last write. Nothing is asked of
 * the core until [loaded] says a game is in it: a core with no game (its load failed) crashes when
 * asked for its save RAM, and the save file is left exactly as it was. [last] starts as the save
 * the game was started with, so an unchanged one isn't written again.
 *
 * The file may be another emulator's (RetroArch's, shared). When the core's save RAM isn't the size
 * of the save the game started with, the two judge the cartridge differently and the core didn't
 * take that save as it was: before the first write over it, the original is kept once beside it
 * ([KEEP_SUFFIX]), never over an earlier keep.
 */
class SramSaver(
    private val file: File,
    private val loaded: () -> Boolean,
    private val read: () -> ByteArray,
    private var last: ByteArray? = null,
) {
    /** The save the game started with, for the size check before the first write. */
    private val startedWith: ByteArray? = last
    private var written = false

    /** Writes the save if a game is loaded and it changed; true when it wrote. */
    @Synchronized
    fun save(): Boolean {
        if (!loaded()) return false
        val sram = try {
            read()
        } catch (e: Exception) {
            Log.w(TAG, "No save from the core: $e")
            return false
        }
        if (sram.isEmpty() || sram.contentEquals(last)) return false
        if (!written) keepOriginal(sram)
        val part = File(file.parentFile, file.name + ".part")
        file.parentFile?.mkdirs()
        part.writeBytes(sram)
        if (!part.renameTo(file)) return false
        written = true
        last = sram
        Log.i(TAG, "Saved ${sram.size} bytes to $file")
        return true
    }

    /** Keeps the started-with save once, when the core's save RAM isn't its size. */
    private fun keepOriginal(sram: ByteArray) {
        val original = startedWith ?: return
        if (original.size == sram.size) return
        val keep = File(file.parentFile, file.name + KEEP_SUFFIX)
        if (keep.exists()) return
        keep.writeBytes(original)
        Log.i(TAG, "Kept the save as it was (${original.size} bytes; the core's is ${sram.size}) at $keep")
    }

    companion object {
        private const val TAG = "SramSaver"
        /** Beside a shared save the core didn't take as it was: the save before Genkan first wrote it. */
        const val KEEP_SUFFIX = ".before-genkan"
    }
}
