// Adapted from Argosy Launcher (GPL-3.0) @ 3480817:
// app/src/main/kotlin/com/nendo/argosy/data/emulator/EmulatorRegistry.kt (the entries for the
// emulators Genkan launches, with their launch recipes) and LibretroCoreRegistry.kt (core ids).
package app.genkan.launch

import android.content.Intent

/** How a ROM is handed to an emulator in one intent extra. */
sealed class ExtraValue {
    /** The plain absolute path. */
    data object FilePath : ExtraValue()
    /** A FileProvider content URI as a Uri extra. */
    data object FileUri : ExtraValue()
    /** A FileProvider content URI as a String extra (an .m3u goes as its path). */
    data object FileUriString : ExtraValue()
    /** The plain path from build [minBuild] on (or for an .m3u), a content URI string below it. */
    data class PathFromBuild(val minBuild: Int) : ExtraValue()
    data class BooleanLiteral(val value: Boolean) : ExtraValue()
}

enum class LaunchMethod {
    INTENT,
    /** `am start` from Genkan's own process; Argosy found it more reliable for some emulators. */
    SHELL,
}

sealed class Recipe {
    /** RetroArch with a named core and its own config file. */
    data class RetroArch(val activity: String = "com.retroarch.browser.retroactivity.RetroActivityFuture") : Recipe()

    /**
     * An emulator activity started with [action]. ACTION_VIEW also binds a content URI as the
     * intent data; other actions carry the ROM only in [extras].
     */
    data class Custom(
        val activity: String,
        val action: String = Intent.ACTION_VIEW,
        val extras: Map<String, ExtraValue> = emptyMap(),
    ) : Recipe()
}

data class EmulatorDef(
    val id: String,
    val packageName: String,
    val displayName: String,
    val platforms: Set<String>,
    val recipe: Recipe,
    val method: LaunchMethod = LaunchMethod.INTENT,
    /** Draws its own second screen on the other display (Azahar's 3DS bottom screen). */
    val drawsBothScreens: Boolean = false,
    val downloadUrl: String? = null,
    /** BIOS files from the platform's firmware in RomM: RomM's file name to the name in [firmwareDir]. */
    val firmware: Map<String, String> = emptyMap(),
    /** The emulator's BIOS folder, relative to shared storage. */
    val firmwareDir: String? = null,
)

const val DUCKSTATION_PLAIN_PATH_MIN_BUILD = 10_000

private val BUILD_NUMBER = Regex("""^\d+\.\d+-(\d+)""")

/** "0.1-10232" -> 10232: DuckStation's build number from its versionName. */
fun buildNumberOf(versionName: String?): Int? =
    versionName?.let { BUILD_NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() }

object EmulatorCatalog {

    private val RETROARCH_PLATFORMS = setOf("nes", "snes", "gb", "gbc", "gba", "n64")

    /** Ordered by preference: the first installed one for a platform is used. */
    val emulators: List<EmulatorDef> = listOf(
        EmulatorDef(
            id = "retroarch_64", packageName = "com.retroarch.aarch64", displayName = "RetroArch (64-bit)",
            platforms = RETROARCH_PLATFORMS, recipe = Recipe.RetroArch(),
            downloadUrl = "https://www.retroarch.com/?page=platforms",
        ),
        EmulatorDef(
            id = "retroarch", packageName = "com.retroarch", displayName = "RetroArch",
            platforms = RETROARCH_PLATFORMS, recipe = Recipe.RetroArch(),
            downloadUrl = "https://www.retroarch.com/?page=platforms",
        ),
        EmulatorDef(
            id = "retroarch_32", packageName = "com.retroarch.ra32", displayName = "RetroArch (32-bit)",
            platforms = RETROARCH_PLATFORMS, recipe = Recipe.RetroArch(),
        ),
        EmulatorDef(
            id = "duckstation", packageName = "com.github.stenzek.duckstation", displayName = "DuckStation",
            platforms = setOf("psx"),
            recipe = Recipe.Custom(
                activity = "com.github.stenzek.duckstation.EmulationActivity",
                action = Intent.ACTION_MAIN,
                extras = mapOf(
                    "bootPath" to ExtraValue.PathFromBuild(DUCKSTATION_PLAIN_PATH_MIN_BUILD),
                    "resumeState" to ExtraValue.BooleanLiteral(false),
                ),
            ),
            downloadUrl = "https://play.google.com/store/apps/details?id=com.github.stenzek.duckstation",
            // It finds BIOS images by their contents; the Japanese one for the Japanese library.
            firmware = mapOf("ps-30j.bin" to "scph5500.bin"),
            firmwareDir = "Android/data/com.github.stenzek.duckstation/files/bios",
        ),
        EmulatorDef(
            id = "armsx2_refresh", packageName = "com.armsx2", displayName = "ARMSX2",
            platforms = setOf("ps2"),
            recipe = Recipe.Custom(activity = "com.armsx2.MainActivity"),
            method = LaunchMethod.SHELL,
            downloadUrl = "https://github.com/ARMSX2/ARMSX2/releases",
        ),
        EmulatorDef(
            id = "armsx2", packageName = "come.nanodata.armsx2", displayName = "ARMSX2 (Play Store)",
            platforms = setOf("ps2"),
            recipe = Recipe.Custom(activity = "kr.co.iefriends.pcsx2.activities.MainActivity"),
            method = LaunchMethod.SHELL,
        ),
        EmulatorDef(
            id = "dolphin", packageName = "org.dolphinemu.dolphinemu", displayName = "Dolphin",
            platforms = setOf("ngc", "wii"),
            recipe = Recipe.Custom(
                activity = "org.dolphinemu.dolphinemu.ui.main.MainActivity",
                action = Intent.ACTION_MAIN,
                extras = mapOf("AutoStartFile" to ExtraValue.FileUri),
            ),
            method = LaunchMethod.SHELL,
            downloadUrl = "https://play.google.com/store/apps/details?id=org.dolphinemu.dolphinemu",
        ),
        EmulatorDef(
            id = "dolphin_handheld", packageName = "org.dolphinemu.handheld", displayName = "Dolphin (Handheld)",
            platforms = setOf("ngc", "wii"),
            recipe = Recipe.Custom(
                activity = "org.dolphinemu.dolphinemu.ui.main.MainActivity",
                action = Intent.ACTION_MAIN,
                extras = mapOf("AutoStartFile" to ExtraValue.FileUri),
            ),
        ),
        EmulatorDef(
            id = "ppsspp", packageName = "org.ppsspp.ppsspp", displayName = "PPSSPP",
            platforms = setOf("psp"),
            recipe = Recipe.Custom(activity = "org.ppsspp.ppsspp.PpssppActivity"),
            method = LaunchMethod.SHELL,
            downloadUrl = "https://play.google.com/store/apps/details?id=org.ppsspp.ppsspp",
        ),
        EmulatorDef(
            id = "ppsspp_gold", packageName = "org.ppsspp.ppssppgold", displayName = "PPSSPP Gold",
            platforms = setOf("psp"),
            recipe = Recipe.Custom(activity = "org.ppsspp.ppsspp.PpssppActivity"),
            method = LaunchMethod.SHELL,
            downloadUrl = "https://play.google.com/store/apps/details?id=org.ppsspp.ppssppgold",
        ),
        EmulatorDef(
            id = "azahar", packageName = "org.azahar_emu.azahar", displayName = "Azahar",
            platforms = setOf("3ds"),
            recipe = Recipe.Custom(
                activity = "org.citra.citra_emu.activities.EmulationActivity",
                extras = mapOf("SelectedGame" to ExtraValue.FilePath),
            ),
            method = LaunchMethod.SHELL,
            drawsBothScreens = true,
            downloadUrl = "https://github.com/azahar-emu/azahar/releases",
        ),
    )

    /**
     * RetroArch core per platform (RomM slugs), from Argosy's defaults. The core file must be
     * installed in RetroArch (Online Updater > Core Downloader).
     */
    val retroArchCores: Map<String, String> = mapOf(
        "nes" to "fceumm",
        "snes" to "snes9x",
        "gb" to "mgba",
        "gbc" to "mgba",
        "gba" to "mgba",
        "n64" to "mupen64plus_next_gles3",
    )

    fun candidatesFor(platformSlug: String): List<EmulatorDef> = emulators.filter { platformSlug in it.platforms }

    val packageNames: Set<String> get() = emulators.map { it.packageName }.toSet()
}
