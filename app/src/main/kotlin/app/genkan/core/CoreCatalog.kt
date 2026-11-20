package app.genkan.core

/**
 * A libretro core bundled with Genkan, by its native library's file name (the linker finds it in
 * the APK). [pointerMask] folds the addresses a game's pointers hold onto the ones its memory
 * map uses, for systems that see RAM through mirrors.
 */
data class CoreDef(
    val id: String,
    val displayName: String,
    val library: String,
    val platforms: Set<String>,
    val variables: Map<String, String> = emptyMap(),
    val pointerMask: Long = 0xFFFFFFFFL,
    /** A zip in the APK's assets/cores the core needs in its system folder, and the folder's name. */
    val assets: Pair<String, String>? = null,
    /** Where the game sees the system RAM, for a core that gives no memory map. */
    val ramBase: Long = 0L,
    /**
     * Folders under shared storage, any of which the core may keep its saves in when it is there
     * (a standalone emulator's, so the two share saves); else Genkan's own.
     */
    val sharedSaves: List<String> = emptyList(),
)

object CoreCatalog {

    val cores: List<CoreDef> = listOf(
        CoreDef(
            id = "mgba", displayName = "mGBA", library = "libmgba_libretro_android.so",
            platforms = setOf("gb", "gbc", "gba"),
        ),
        CoreDef(
            id = "pcsx_rearmed", displayName = "PCSX ReARMed", library = "libpcsx_rearmed_libretro_android.so",
            platforms = setOf("psx"),
            // The PS1's RAM is mirrored at 0x80000000 and 0xA0000000: games point through those.
            pointerMask = 0x1FFFFFFFL,
        ),
        CoreDef(
            id = "ppsspp", displayName = "PPSSPP", library = "libppsspp_libretro_android.so",
            platforms = setOf("psp"),
            // Three times the PSP's 480x272: sharp on the Thor's 1080p screen.
            variables = mapOf("ppsspp_internal_resolution" to "1440x816"),
            // User RAM at 0x08000000 is also seen at 0x48000000 (uncached) and 0x88000000 (kernel).
            pointerMask = 0x3FFFFFFFL,
            assets = "ppsspp.zip" to "PPSSPP",
            ramBase = 0x08000000L,
            // The core's saves folder is its memory stick: PPSSPP's own, so PSP/SAVEDATA is shared.
            sharedSaves = listOf("ppsspp", "PPSSPP"),
        ),
    )

    fun forPlatform(platformSlug: String): CoreDef? = cores.firstOrNull { platformSlug in it.platforms }

    val platforms: Set<String> get() = cores.flatMap { it.platforms }.toSet()
}
