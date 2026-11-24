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
    /** The system keeps its words big-endian (a GameCube): its pointers and UTF-16 text. */
    val bigEndian: Boolean = false,
    /**
     * The libretro device each controller port gets once the game is loaded, by port; empty, the
     * core's own default. Dolphin's ports start empty: without this no controller is plugged in.
     */
    val portDevices: List<Int> = emptyList(),
    /**
     * The console has sticks of its own: the left stick is only its main stick and the right only
     * its second, never also the D-pad, which Android otherwise makes of an unhandled stick.
     */
    val sticksOnly: Boolean = false,
    /**
     * A folder under shared storage the core keeps everything in as its saves folder (Dolphin's
     * User folder: memory cards, and its config and shader cache), instead of Genkan's own files.
     */
    val savesFolder: String? = null,
) {
    /** Whether a file named [name] is one the core plays. */
    fun takes(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in extensions
}

object CoreCatalog {

    /** libretro's RETRO_DEVICE_JOYPAD. */
    const val RETRO_DEVICE_JOYPAD = 1

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
        CoreDef(
            id = "bsnes_mercury", displayName = "bsnes-mercury", library = "libbsnes_mercury_libretro_android.so",
            platforms = setOf("snes", "sfam"),
            // Pointers are 24-bit. The core's memory map gives the bus's own addresses (work RAM at
            // 0x7E0000, mirrored low in banks 00-3F), so no base is added to them.
            pointerMask = 0xFFFFFFL,
            extensions = setOf("sfc", "smc", "bml"),
        ),
        CoreDef(
            id = "dolphin", displayName = "Dolphin", library = "libdolphin_libretro_android.so",
            platforms = setOf("ngc"),
            // Measured on the Thor: a steady 60 fps at the native resolution with the GL renderer
            // (Dolphin's software renderer crashes on start). Fastmem stays at its default, on.
            variables = mapOf("dolphin_renderer" to "Hardware", "dolphin_efb_scale" to "1"),
            // MEM1 is mapped at 0x80000000, as games see it cached; 0xC0000000 is the same RAM uncached.
            pointerMask = 0x81FFFFFFL,
            // Its Sys folder: per-game settings, fonts and shaders, built with the core.
            assets = "dolphin.zip" to "dolphin-emu",
            extensions = setOf("rvz", "iso", "gcm", "gcz", "ciso"),
            bigEndian = true,
            savesFolder = "Genkan/saves/dolphin",
            // A GameCube controller in port 1: Dolphin plugs one in for any device but none.
            portDevices = listOf(RETRO_DEVICE_JOYPAD),
            // Games put menus on its D-pad: the stick mustn't press it too.
            sticksOnly = true,
        ),
    )

    fun forPlatform(platformSlug: String): CoreDef? = cores.firstOrNull { platformSlug in it.platforms }

    val platforms: Set<String> get() = cores.flatMap { it.platforms }.toSet()
}
