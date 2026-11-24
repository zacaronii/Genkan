package app.genkan.core

import android.os.Environment
import app.genkan.saves.SaveLocator
import app.genkan.saves.SaveTarget
import java.io.File

/**
 * Where the built-in player keeps a game's battery save: the same file RetroArch's core would
 * use when Genkan already syncs that one, so the two players share saves; else Genkan's own
 * folder, by core.
 */
object CoreSaves {
    /** The folder a core keeps its own saves in: a standalone emulator's when it has one here. */
    fun directoryFor(core: CoreDef, fallback: File, storage: File = Environment.getExternalStorageDirectory()): File =
        core.savesFolder?.let { File(storage, it) }
            ?: core.sharedSaves.map { File(storage, it) }.firstOrNull { File(it, "PSP/SAVEDATA").isDirectory } ?: fallback

    fun fileFor(platformSlug: String, core: CoreDef, rom: File, storage: File = Environment.getExternalStorageDirectory()): File =
        SaveLocator(storage).locate(platformSlug, rom)?.takeIf { !it.folder }?.path
            ?: ownFile(core, rom, storage)

    /**
     * The save to sync when the built-in player runs the game and keeps it in Genkan's own folder,
     * filed in RomM under the core's name; null when it shares a standalone emulator's save,
     * which syncs as that emulator's.
     */
    fun ownTarget(platformSlug: String, core: CoreDef, rom: File, storage: File = Environment.getExternalStorageDirectory()): SaveTarget? {
        if (core.sharedSaves.isNotEmpty() || core.savesFolder != null || SaveLocator(storage).locate(platformSlug, rom)?.folder == false) return null
        val file = ownFile(core, rom, storage)
        return SaveTarget(core.id, file, folder = false, name = file.name)
    }

    private fun ownFile(core: CoreDef, rom: File, storage: File) = File(storage, "Genkan/saves/${core.id}/${rom.nameWithoutExtension}.srm")
}
