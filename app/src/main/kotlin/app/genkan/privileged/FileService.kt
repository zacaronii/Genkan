package app.genkan.privileged

import java.io.File
import kotlin.system.exitProcess

/**
 * Runs in a separate process that Shizuku starts with the shell user's identity, which may read
 * and write other apps' Android/data folders. Keep it to plain file work on paths Genkan names.
 * Genkan also uses it in-process for save folders it can reach itself (PPSSPP's memory stick).
 */
class FileService : IFileService.Stub() {

    /** Shizuku's teardown of the helper process. Never call it on an in-process instance. */
    override fun destroy() {
        exitProcess(0)
    }

    override fun list(dir: String): Array<String> {
        val root = File(dir)
        if (!root.isDirectory) return emptyArray()
        return root.walkTopDown().filter { it.isFile }
            .map { "${it.relativeTo(root).invariantSeparatorsPath}\t${it.length()}\t${it.lastModified()}" }
            .toList().toTypedArray()
    }

    override fun copy(from: String, to: String): Boolean = try {
        val source = File(from)
        val target = File(to)
        if (source.isDirectory) {
            target.mkdirs()
            source.copyRecursively(target, overwrite = true)
        } else {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            // The emulator compares times; a restored save keeps the time it was made.
            target.setLastModified(source.lastModified())
            true
        }
    } catch (_: Exception) {
        false
    }

    override fun deleteRecursively(path: String): Boolean = File(path).deleteRecursively()
}
