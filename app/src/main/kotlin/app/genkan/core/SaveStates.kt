package app.genkan.core

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A game's save states in the built-in player: slots 1 to [SLOTS], and [BEFORE_LOAD], the state
 * taken just before a load so a mis-press can be undone. Each is the core's state, gzipped, after
 * a line naming the core that wrote it ([header]): a state is only handed back to that core.
 * Kept in [dir] (Genkan's own storage, apart from the battery save, never synced): saving into a
 * used slot keeps the state it held once, as `.bak`.
 */
class SaveStates(private val dir: File, private val core: String) {

    sealed interface Read {
        class State(val bytes: ByteArray, val savedAt: Long) : Read
        data object Empty : Read
        /** A state, but another core's: it can't be loaded into this one. */
        data class OtherCore(val core: String) : Read
        data class Unreadable(val reason: String) : Read
    }

    fun file(slot: Int): File = File(dir, if (slot == BEFORE_LOAD) "before-load.state.gz" else "slot$slot.state.gz")

    /** When [slot] was saved, or null when it's empty. */
    fun savedAt(slot: Int): Long? = file(slot).takeIf { it.isFile }?.lastModified()

    /** Writes [state] into [slot]; what the slot held is kept as its `.bak` (the one before it is let go). */
    fun write(slot: Int, state: ByteArray) {
        dir.mkdirs()
        val target = file(slot)
        val part = File(dir, target.name + ".part")
        GZIPOutputStream(part.outputStream()).use {
            it.write(header(core))
            it.write(state)
        }
        if (target.isFile && slot != BEFORE_LOAD) {
            val bak = File(dir, target.name + ".bak")
            bak.delete()
            target.renameTo(bak)
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("couldn't save it")
        }
    }

    fun read(slot: Int): Read {
        val file = file(slot)
        if (!file.isFile) return Read.Empty
        val data = try {
            GZIPInputStream(file.inputStream()).use { it.readBytes() }
        } catch (e: EOFException) {
            return Read.Unreadable("the file is cut short")
        } catch (e: IOException) {
            return Read.Unreadable(e.message ?: "it can't be read")
        }
        val end = data.indexOf('\n'.code.toByte()).takeIf { it in 1..MAX_HEADER }
            ?: return Read.Unreadable("it isn't a Genkan state")
        val line = String(data, 0, end, Charsets.US_ASCII).split(' ')
        if (line.size != 3 || line[0] != MAGIC || line[1] != VERSION) return Read.Unreadable("it isn't a Genkan state")
        if (line[2] != core) return Read.OtherCore(line[2])
        val bytes = data.copyOfRange(end + 1, data.size)
        if (bytes.isEmpty()) return Read.Unreadable("it's empty")
        return Read.State(bytes, file.lastModified())
    }

    companion object {
        const val SLOTS = 4
        /** The folder in Genkan's files the states are kept in. */
        const val FOLDER = "states"
        /** The state the game was in before the last load. */
        const val BEFORE_LOAD = 0
        private const val MAGIC = "GENKAN-STATE"
        private const val VERSION = "1"
        private const val MAX_HEADER = 128

        fun header(core: String): ByteArray = "$MAGIC $VERSION $core\n".toByteArray(Charsets.US_ASCII)

        /** Where a game's states are kept, under [root] (`files/states`). */
        fun dirFor(root: File, core: String, gameKey: String): File = File(root, "$core/$gameKey")

        /** Removes a removed game's states, whichever core wrote them. */
        fun forget(root: File, gameKey: String) {
            root.listFiles()?.forEach { File(it, gameKey).deleteRecursively() }
        }
    }
}
