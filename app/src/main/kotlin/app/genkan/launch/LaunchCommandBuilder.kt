// The per-recipe rules are adapted from Argosy Launcher (GPL-3.0) @ 3480817:
// app/src/main/kotlin/com/nendo/argosy/data/emulator/GameLauncher.kt (commandForRetroArch and the
// custom-config branch).
package app.genkan.launch

import android.content.Intent
import java.io.File

/** Builds the [LaunchCommand] for one game and emulator. Pure, so every recipe is unit tested. */
class LaunchCommandBuilder(
    /** Primary shared storage root, e.g. /storage/emulated/0. */
    private val externalRoot: String,
    /** FileProvider content URI for a file Genkan downloaded. */
    private val contentUri: (File) -> String,
    private val mimeTypeOf: (File) -> String = { "application/octet-stream" },
) {

    fun build(emulator: EmulatorDef, rom: File, platformSlug: String, installedVersionName: String?): LaunchCommand? =
        when (val recipe = emulator.recipe) {
            is Recipe.RetroArch -> retroArch(emulator, recipe, rom, platformSlug)
            is Recipe.Custom -> custom(emulator, recipe, rom, installedVersionName)
        }

    // RetroArch's own data dir, which its intent API takes as a literal path (LIBRETRO, DATADIR).
    @android.annotation.SuppressLint("SdCardPath")
    private fun retroArch(emulator: EmulatorDef, recipe: Recipe.RetroArch, rom: File, platformSlug: String): LaunchCommand? {
        val core = EmulatorCatalog.retroArchCores[platformSlug] ?: return null
        val pkg = emulator.packageName
        val dataDir = "/data/data/$pkg"
        val externalDir = "$externalRoot/Android/data/$pkg/files"
        val uri = contentUri(rom)
        return LaunchCommand(
            action = Intent.ACTION_MAIN,
            packageName = pkg,
            activity = recipe.activity,
            extras = listOf(
                ResolvedExtra.StringExtra("ROM", rom.absolutePath),
                ResolvedExtra.StringExtra("LIBRETRO", "$dataDir/cores/${core}_libretro_android.so"),
                ResolvedExtra.StringExtra("CONFIGFILE", "$externalDir/retroarch.cfg"),
                ResolvedExtra.StringExtra("IME", "com.android.inputmethod.latin/.LatinIME"),
                ResolvedExtra.StringExtra("DATADIR", dataDir),
                ResolvedExtra.StringExtra("SDCARD", externalRoot),
                ResolvedExtra.StringExtra("EXTERNAL", externalDir),
            ),
            flags = ONE_SHOT_FLAGS or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            grantReadUris = listOf(uri),
            clipDataUri = uri,
            method = emulator.method,
        )
    }

    private fun custom(emulator: EmulatorDef, recipe: Recipe.Custom, rom: File, versionName: String?): LaunchCommand {
        val isM3u = rom.extension.equals("m3u", ignoreCase = true)
        var providerUri: String? = null
        fun uri(): String = providerUri ?: contentUri(rom).also { providerUri = it }

        val extras = recipe.extras.map { (key, value) ->
            when (value) {
                ExtraValue.FilePath -> ResolvedExtra.StringExtra(key, rom.absolutePath)
                ExtraValue.FileUri -> ResolvedExtra.UriExtra(key, uri())
                ExtraValue.FileUriString -> ResolvedExtra.StringExtra(key, if (isM3u) rom.absolutePath else uri())
                is ExtraValue.PathFromBuild -> {
                    val plain = isM3u || (buildNumberOf(versionName) ?: 0) >= value.minBuild
                    ResolvedExtra.StringExtra(key, if (plain) rom.absolutePath else uri())
                }
                is ExtraValue.BooleanLiteral -> ResolvedExtra.BoolExtra(key, value.value)
            }
        }

        if (recipe.action == Intent.ACTION_VIEW) {
            val data = uri()
            return LaunchCommand(
                action = recipe.action,
                packageName = emulator.packageName,
                activity = recipe.activity,
                dataUri = data,
                mimeType = mimeTypeOf(rom),
                extras = extras,
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION,
                grantReadUris = listOf(data),
                clipDataUri = data,
                method = emulator.method,
            )
        }

        val granted = listOfNotNull(providerUri)
        return LaunchCommand(
            action = recipe.action,
            packageName = emulator.packageName,
            activity = recipe.activity,
            extras = extras,
            flags = ONE_SHOT_FLAGS or if (granted.isNotEmpty()) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0,
            grantReadUris = granted,
            clipDataUri = granted.firstOrNull(),
            method = emulator.method,
        )
    }

    companion object {
        /** A ROM that rides on extras is launched as a one-shot, so it never lingers in recents. */
        const val ONE_SHOT_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY

        /**
         * The file to boot inside a multi-file game folder: its playlist if there is one; for
         * numbered discs without one, a playlist Genkan writes, disc 1 first, so the emulator
         * can change discs; then a cue sheet, then the largest file.
         */
        fun bootFileIn(folder: File): File? {
            val files = folder.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.toList()
            files.firstOrNull { it.extension.equals("m3u", true) }?.let { return it }
            val cues = files.filter { it.extension.equals("cue", true) }
            // Each disc's cue sheet stands for it; without them, its image does (not a cue's tracks).
            val images = cues.ifEmpty { files.filter { it.extension.lowercase() !in TRACK_EXTENSIONS } }
            val discs = images.mapNotNull { file -> DISC.find(file.name)?.let { it.groupValues[1].toInt() to file } }
            if (discs.size > 1 && discs.size == images.size) {
                val playlist = File(folder, folder.name + ".m3u")
                val lines = discs.sortedBy { it.first }.map { it.second.relativeTo(folder).invariantSeparatorsPath }
                return try {
                    playlist.writeText(lines.joinToString("\n", postfix = "\n"))
                    playlist
                } catch (e: java.io.IOException) {
                    discs.minBy { it.first }.second
                }
            }
            return cues.firstOrNull() ?: files.maxByOrNull { it.length() }
        }

        /** The first file [playlist] lists that is there, or null: disc 1, for an emulator that can't open a playlist. */
        fun firstListed(playlist: File): File? = playlist.readLines().asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { File(it).takeIf(File::isAbsolute) ?: File(playlist.parentFile, it) }
            .firstOrNull { it.isFile }

        /** "Disc 2", "Disk 2", "CD2": a disc's number in its file name. */
        private val DISC = Regex("""(?i)\b(?:disc|disk|cd)\s*(\d+)""")

        /** The tracks a cue sheet lists, never booted on their own. */
        private val TRACK_EXTENSIONS = setOf("bin", "img", "wav", "ape", "flac", "mp3", "ogg", "sub", "sbi")
    }
}
