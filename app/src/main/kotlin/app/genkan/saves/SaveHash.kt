package app.genkan.saves

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * RomM's save content hash, so the server can tell the device's copy from its own: the MD5 of a
 * plain file's bytes, and for a zip the MD5 of "name:md5" lines, one per file entry, sorted by
 * name (backend/handler/filesystem/assets_handler.py). Folder saves travel as such zips.
 */
object SaveHash {

    fun ofFile(file: File): String = file.inputStream().use(::md5)

    /** The hash RomM gives [zip]: a plain file's hash when it isn't a zip. */
    fun ofUpload(zip: File): String = if (isZip(zip)) ofZip(zip) else ofFile(zip)

    fun ofZip(zip: File): String = ZipFile(zip).use { zf ->
        val lines = zf.entries().asSequence().filter { !it.isDirectory }.map { it.name }.sorted()
            .map { name -> "$name:${zf.getInputStream(zf.getEntry(name)).use(::md5)}" }
        md5(lines.joinToString("\n").byteInputStream())
    }

    /** Zips [folder]'s files with paths relative to it, in name order, so the bytes and hash repeat. */
    fun zipFolder(folder: File, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            for ((name, file) in filesOf(folder)) {
                out.putNextEntry(ZipEntry(name).apply { time = 0 })
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    /** The hash [zipFolder] would give, without writing the zip. */
    fun ofFolder(folder: File): String =
        md5(filesOf(folder).joinToString("\n") { (name, file) -> "$name:${ofFile(file)}" }.byteInputStream())

    fun filesOf(folder: File): List<Pair<String, File>> =
        folder.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(folder).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .toList()

    private fun isZip(file: File): Boolean = file.inputStream().use { input ->
        val head = ByteArray(4)
        input.read(head) == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()
    }

    private fun md5(input: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
