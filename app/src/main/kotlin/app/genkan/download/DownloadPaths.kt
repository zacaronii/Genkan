package app.genkan.download

import app.genkan.romm.RomMRomFile
import java.io.File

/**
 * Where games land: `<root>/<platform>/<file>` for one file, `<root>/<platform>/<game>/...`
 * for a multi-file game, keeping the server's layout so .m3u playlists still resolve.
 */
object DownloadPaths {

    fun gamePath(root: File, platformFolder: String, fsName: String): File =
        File(File(root, safe(platformFolder)), safe(fsName))

    /**
     * Where [file] goes inside a multi-file game. RomM reports `file_path` from its library root
     * (`roms/ps2/<game>/sub`), so the game's own `full_path` is cut off and the rest kept.
     */
    fun fileInGame(gameDir: File, gameFullPath: String, file: RomMRomFile): File {
        val prefix = gameFullPath.trim('/')
        val relativeDir = file.filePath.trim('/').removePrefix(prefix).trim('/')
        val dir = relativeDir.split('/').filter { it.isNotEmpty() }.fold(gameDir) { acc, segment -> File(acc, safe(segment)) }
        return File(dir, safe(file.fileName))
    }

    /**
     * Files that belong to the game. NAS sidecar folders (Synology's @eaDir) are dropped; dot
     * files are kept, because hidden disc images behind an .m3u are a common layout.
     */
    fun isGameFile(file: RomMRomFile): Boolean =
        (file.filePath.split('/', '\\') + file.fileName).none { it.equals("@eaDir", ignoreCase = true) }

    /** Path segments from the server never climb out of the downloads folder. */
    internal fun safe(segment: String): String {
        val cleaned = segment.replace('/', '_').replace('\\', '_').replace('\u0000', '_')
        return if (cleaned == "." || cleaned == ".." || cleaned.isBlank()) "_" else cleaned
    }
}
