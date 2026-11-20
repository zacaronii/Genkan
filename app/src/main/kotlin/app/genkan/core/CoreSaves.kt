package app.genkan.core

import android.os.Environment
import app.genkan.saves.SaveLocator
import java.io.File

/**
 * Where the built-in player keeps a game's battery save: the same file RetroArch's core would
 * use when Genkan already syncs that one, so the two players share saves; else Genkan's own
 * folder, by core.
 */
object CoreSaves {
    /** The folder a core keeps its own saves in: a standalone emulator's when it has one here. */
    fun directoryFor(core: CoreDef, fallback: File, storage: File = Environment.getExternalStorageDirectory()): File =
        core.sharedSaves.map { File(storage, it) }.firstOrNull { File(it, "PSP/SAVEDATA").isDirectory } ?: fallback

    fun fileFor(platformSlug: String, core: CoreDef, rom: File, storage: File = Environment.getExternalStorageDirectory()): File =
        SaveLocator(storage).locate(platformSlug, rom)?.takeIf { !it.folder }?.path
            ?: File(storage, "Genkan/saves/${core.id}/${rom.nameWithoutExtension}.srm")
}
