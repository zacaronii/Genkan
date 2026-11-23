package app.genkan.core

import android.util.Log
import java.io.File

/**
 * Writes a game's battery save to [file] when it changed since the last write. Nothing is asked of
 * the core until [loaded] says a game is in it: a core with no game (its load failed) crashes when
 * asked for its save RAM, and the save file is left exactly as it was. [last] starts as the save
 * the game was started with, so an unchanged one isn't written again.
 */
class SramSaver(
    private val file: File,
    private val loaded: () -> Boolean,
    private val read: () -> ByteArray,
    private var last: ByteArray? = null,
) {
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
        val part = File(file.parentFile, file.name + ".part")
        file.parentFile?.mkdirs()
        part.writeBytes(sram)
        if (!part.renameTo(file)) return false
        last = sram
        Log.i(TAG, "Saved ${sram.size} bytes to $file")
        return true
    }

    private companion object {
        const val TAG = "SramSaver"
    }
}
