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
    /**
     * BIOS files from the platform's firmware in RomM the core boots with: RomM's file name to
     * the name the core looks for in its system folder.
     */
    val firmware: Map<String, String> = emptyMap(),
    /** The core stacks two screens in one frame (a DS): the lower one goes to the bottom display. */
    val splitScreens: Boolean = false,
    /** The file types the core plays, lower case, without the dot: the core's own list. */
    val extensions: Set<String> = emptySet(),
) {
    /** Whether a file named [name] is one the core plays. */
    fun takes(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in extensions
}

object CoreCatalog {

    val cores: List<CoreDef> = listOf(
        CoreDef(
            id = "mgba", displayName = "mGBA", library = "libmgba_libretro_android.so",
            platforms = setOf("gb", "gbc", "gba"),
            extensions = setOf("gb", "gbc", "sgb", "gba"),
        ),
        CoreDef(
            id = "pcsx_rearmed", displayName = "PCSX ReARMed", library = "libpcsx_rearmed_libretro_android.so",
            platforms = setOf("psx"),
            // The PS1's RAM is mirrored at 0x80000000 and 0xA0000000: games point through those.
            pointerMask = 0x1FFFFFFFL,
            // Without a BIOS it emulates one, which some games hang on. The Japanese one (SCPH-5500,
            // v3.0J) for Japanese discs, under a name the core picks up; it would prefer a US one.
            firmware = mapOf("ps-30j.bin" to "scph5500.bin"),
            extensions = setOf("bin", "cue", "img", "mdf", "pbp", "toc", "cbn", "m3u", "ccd", "chd", "iso", "exe"),
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
            extensions = setOf("elf", "iso", "cso", "prx", "pbp", "chd"),
        ),
        CoreDef(
            id = "melonds", displayName = "melonDS DS", library = "libmelonds_libretro_android.so",
            platforms = setOf("nds"),
            variables = mapOf(
                // One frame, upper screen over lower and nothing between, which Genkan splits.
                "melonds_number_of_screen_layouts" to "1",
                "melonds_screen_layout1" to "top-bottom",
                "melonds_screen_gap" to "0",
                "melonds_touch_mode" to "touch",
                // For carts with more than one language, the one the console is set to.
                "melonds_firmware_language" to "ja",
                // Frames in memory, not on the GPU: the split needs their pixels.
                "melonds_render_mode" to "software",
                "melonds_threaded_renderer" to "enabled",
            ),
            // Main RAM is 4 MB at 0x02000000, mirrored up to 0x03000000.
            pointerMask = 0x023FFFFFL,
            ramBase = 0x02000000L,
            splitScreens = true,
            extensions = setOf("nds", "ids", "dsi"),
        ),
    )

    fun forPlatform(platformSlug: String): CoreDef? = cores.firstOrNull { platformSlug in it.platforms }

    val platforms: Set<String> get() = cores.flatMap { it.platforms }.toSet()
}
