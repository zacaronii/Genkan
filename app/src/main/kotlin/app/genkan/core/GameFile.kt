package app.genkan.core

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * The file a core is handed for a downloaded game. Cores take their own file types ([CoreDef.extensions]);
 * a game downloaded as a zip is unpacked first, into a folder of Genkan's own, since the cores here
 * read the file as it is and refuse an archive. The zip itself stays as it was (outside emulators
 * unpack it themselves), and the save keeps the downloaded file's name.
 */
object GameFile {

    /** What [prepare] found: the file to play, with a note when it chose among several; or why there's none. */
    sealed class Prepared {
        data class Ready(val file: File, val note: String? = null) : Prepared()
        data class Unplayable(val reason: String) : Prepared()
    }

    /** Whether [core] can play [rom]: a file of its own type, or a zip holding one. */
    fun takes(core: CoreDef, rom: File): Boolean = when {
        core.takes(rom.name) -> true
        isZip(rom) -> try {
            ZipFile(rom).use { zip -> playable(core, zip).isNotEmpty() }
        } catch (e: IOException) {
            false
        }
        else -> false
    }

    /**
     * The file to hand [core] for [rom]: [rom] itself when the core takes it; for a zip, its first
     * entry (by name, folders included) the core takes, unpacked under [cacheRoot] in a folder for
     * [gameKey] and this copy of the zip (its size and time), and reused while those stay the same.
     * An older copy's folder for the same game is removed. Entries are written by their own name
     * only, never a path, and no more than [maxBytes] of one.
     */
    fun prepare(core: CoreDef, rom: File, cacheRoot: File, gameKey: String, maxBytes: Long = MAX_UNPACKED_BYTES): Prepared {
        if (core.takes(rom.name)) return Prepared.Ready(rom)
        if (!isZip(rom)) return Prepared.Unplayable("${core.displayName} can't play a .${rom.extension} file")
        val zip = try {
            ZipFile(rom)
        } catch (e: IOException) {
            return Prepared.Unplayable("The game's zip can't be read: ${e.message}")
        }
        zip.use {
            val entries = playable(core, zip)
            val entry = entries.firstOrNull()
                ?: return Prepared.Unplayable("The game's zip holds nothing ${core.displayName} can play")
            val name = File(entry.name).name
            if (name.isEmpty() || name == "." || name == "..") return Prepared.Unplayable("The game's zip has an entry with no usable name")
            if (entry.size > maxBytes) return Prepared.Unplayable("The game is too large to unpack (${entry.size / MB} MB)")
            val folder = File(cacheRoot, folderName(gameKey, rom))
            val target = File(folder, name)
            val note = if (entries.size > 1) "Playing ${entry.name}, the first of ${entries.size} files in the zip" else null
            if (File(folder, DONE).isFile && target.isFile) return Prepared.Ready(target, note)
            // Another copy of this game's zip (replaced, or downloaded again): its files go.
            forget(cacheRoot, gameKey)
            folder.mkdirs()
            if (entry.size > 0 && cacheRoot.usableSpace < entry.size + SPACE_MARGIN_BYTES) {
                return Prepared.Unplayable("Not enough free space to unpack the game (${entry.size / MB} MB)")
            }
            val part = File(folder, "$name.part")
            try {
                zip.getInputStream(entry).use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var written = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            // The entry's own size can lie: the count is what holds.
                            if (written > maxBytes) throw IOException("more than ${maxBytes / MB} MB")
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (!part.renameTo(target)) throw IOException("couldn't keep the unpacked file")
                File(folder, DONE).writeText(entry.name)
            } catch (e: IOException) {
                folder.deleteRecursively()
                return Prepared.Unplayable("The game couldn't be unpacked: ${e.message}")
            }
            return Prepared.Ready(target, note)
        }
    }

    /** Removes everything unpacked for [gameKey] (its game was removed, or its zip changed). */
    fun forget(cacheRoot: File, gameKey: String) {
        cacheRoot.listFiles { file -> file.isDirectory && file.name.startsWith("$gameKey-") }?.forEach { it.deleteRecursively() }
    }

    /** The key a game's unpacked files are kept under: its RomM id, or its file's path when it has none. */
    fun keyFor(romId: Long, rom: File): String =
        if (romId > 0) romId.toString() else "f" + Integer.toHexString(rom.absolutePath.hashCode())

    /** The entries of [zip] [core] can play, by name. */
    private fun playable(core: CoreDef, zip: ZipFile): List<ZipEntry> =
        zip.entries().asSequence().filter { !it.isDirectory && core.takes(it.name) }.sortedBy { it.name }.toList()

    private fun isZip(file: File) = file.extension.equals("zip", ignoreCase = true)

    private fun folderName(gameKey: String, rom: File) = "$gameKey-${rom.length()}-${rom.lastModified()}"

    /** Where unpacked games are kept: app storage the system doesn't clear and that isn't backed up. */
    fun cacheRoot(noBackupDir: File) = File(noBackupDir, "unpacked")

    /** The largest game unpacked: above any DS or GBA cartridge. */
    const val MAX_UNPACKED_BYTES = 1L shl 30
    private const val SPACE_MARGIN_BYTES = 64L shl 20
    private const val BUFFER_BYTES = 1 shl 16
    private const val MB = 1L shl 20
    /** Written last into a folder whose file is whole. */
    private const val DONE = ".done"
}
