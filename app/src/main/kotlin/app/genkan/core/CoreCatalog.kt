package app.genkan.core

import app.genkan.BuildConfig

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
    /**
     * Its ladder (TuningLadders) and the catalog's key for its tuning: a console played by another's
     * core (a Wii by the GameCube's Dolphin) shares that core's.
     */
    val tuning: String = id,
    /** A lifted finger leaves the game's pointer where it was (a Wii's: aimed, then left there). */
    val keepPointer: Boolean = false,
    /**
     * Only in debug builds, whose library holds it: one of two cores tried side by side for a
     * console until a round on the device picks one (see [CoreCatalog.debugChoice]).
     */
    val debugOnly: Boolean = false,
    /**
     * A debug-only core for a console that already plays elsewhere (the PS2 in ARMSX2): it plays
     * only once the debug CORE broadcast chooses it, so a debug build otherwise keeps the console
     * where it was.
     */
    val optIn: Boolean = false,
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
            id = "beetle_pce_fast", displayName = "Beetle PCE Fast", library = "libmednafen_pce_fast_libretro_android.so",
            // HuCards, and CD-ROM² discs, which boot through a System Card.
            platforms = setOf("tg16", "turbografx-cd"),
            // System Card 3, which CD-ROM² and Super CD-ROM² discs both boot with. RomM keeps it
            // in the CD platform's firmware; HuCards need none.
            variables = mapOf("pce_fast_cdbios" to "System Card 3"),
            firmware = mapOf("syscard3.pce" to "syscard3.pce"),
            extensions = setOf("pce", "cue", "ccd", "chd", "toc", "m3u"),
        ),
        // The Saturn's two candidates, built from source in debug builds only, until a round on the
        // device picks one. Both boot with the Japanese BIOS from RomM's Saturn firmware.
        CoreDef(
            // An ARM64 dynarec for the SH-2s and a GLES3 renderer: made for phones.
            id = "yabasanshiro", displayName = "YabaSanshiro", library = "libyabasanshiro_libretro_android.so",
            platforms = setOf("saturn"),
            firmware = mapOf("sega_101.bin" to "sega_101.bin"),
            extensions = setOf("cue", "iso", "mds", "ccd", "chd"),
            debugOnly = true,
        ),
        CoreDef(
            // Mednafen's: the most accurate, its SH-2s interpreted.
            id = "beetle_saturn", displayName = "Beetle Saturn", library = "libmednafen_saturn_libretro_android.so",
            platforms = setOf("saturn"),
            firmware = mapOf("sega_101.bin" to "sega_101.bin"),
            // Not zip, which the core reads as an arcade board's: a zipped disc is unpacked first.
            extensions = setOf("cue", "ccd", "chd", "toc", "m3u"),
            debugOnly = true,
        ),
        CoreDef(
            id = "flycast", displayName = "Flycast", library = "libflycast_libretro_android.so",
            platforms = setOf("dc"),
            variables = mapOf(
                // A Japanese console: its BIOS's language and the region discs see.
                "reicast_region" to "Japan",
                "reicast_language" to "Japanese",
            ),
            // The BIOS and its flash from RomM's Dreamcast firmware, in the dc folder the core looks in.
            firmware = mapOf("dc_boot.bin" to "dc/dc_boot.bin", "dc_flash.bin" to "dc/dc_flash.bin"),
            // The core gives its 16 MB of RAM without a map: games see it at 0x0C000000, and point at
            // it through the 0x8C000000 and 0xAC000000 mirrors.
            pointerMask = 0x1FFFFFFFL,
            ramBase = 0x0C000000L,
            // Not zip or 7z, which the core reads as an arcade board's: a zipped disc is unpacked first.
            extensions = setOf("chd", "cdi", "gdi", "cue", "m3u"),
        ),
        CoreDef(
            // libretro's PCSX2 with native ARM64 recompilers, built from source; in debug builds
            // only, and only when chosen, until a round on the device shows it at full speed. PS2
            // games otherwise go to ARMSX2.
            id = "lrps2", displayName = "LRPS2", library = "libpcsx2_libretro_android.so",
            platforms = setOf("ps2"),
            variables = mapOf(
                // libretrodroid gives cores OpenGL ES.
                "pcsx2_renderer" to "OpenGL",
                "pcsx2_bios" to "ps2-0230a-20080220.bin",
            ),
            // RomM's PS2 BIOS, in the folder the core looks in.
            firmware = mapOf("ps2-0230a-20080220.bin" to "pcsx2/bios/ps2-0230a-20080220.bin"),
            // The core gives the EE's 32 MB without a map, from address 0; games also point at it
            // through the uncached views at 0x20000000 and 0x30000000.
            pointerMask = 0x01FFFFFFL,
            extensions = setOf("iso", "chd", "cso", "zso", "ciso", "cue", "elf", "m3u"),
            debugOnly = true,
            optIn = true,
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
        CoreDef(
            // The same core and library as the GameCube's: Dolphin plays a Wii disc as one.
            id = "dolphin_wii", displayName = "Dolphin", library = "libdolphin_libretro_android.so",
            platforms = setOf("wii"),
            variables = mapOf(
                "dolphin_renderer" to "Hardware", "dolphin_efb_scale" to "1",
                // A Wiimote with a Nunchuk, the controller most games play with, and those for the
                // Wiimote alone too. The core plugs it in for any device in port 1 but none.
                "dolphin_wiimote_default_device" to "nunchuk",
                // The pointer on the touch screen (the core's "mouse" mode reads libretro's pointer),
                // so the right stick stays the Wiimote's tilt and swing.
                "dolphin_ir_mode" to "2",
                // The console's own settings (its SYSCONF): a wide picture, in Japanese, the sensor
                // bar under the screen. No NAND is needed: the core makes an empty one.
                "dolphin_widescreen" to "enabled",
                "dolphin_language" to "0",
                "dolphin_sensor_bar_position" to "0",
            ),
            // MEM1 at 0x80000000 and MEM2 (64 MB) at 0x90000000, as games see them cached;
            // 0xC0000000 and 0xD0000000 are the same RAM uncached.
            pointerMask = 0x93FFFFFFL,
            assets = "dolphin.zip" to "dolphin-emu",
            extensions = setOf("rvz", "iso", "wbfs", "wia", "gcz", "ciso"),
            bigEndian = true,
            // The GameCube's User folder: Wii saves go to its Wii/title.
            savesFolder = "Genkan/saves/dolphin",
            portDevices = listOf(RETRO_DEVICE_JOYPAD),
            sticksOnly = true,
            tuning = "dolphin",
            keepPointer = true,
        ),
    )

    /**
     * In a debug build, which of a console's debug-only cores plays its games, by platform: a core
     * id (set by the debug CORE broadcast, for this run of the app). Absent, the first listed.
     */
    @Volatile var debugChoice: Map<String, String> = emptyMap()

    /** The core for RomM's platform [platformSlug]; [debug]: whether debug-only cores count (a debug build). */
    fun forPlatform(platformSlug: String, debug: Boolean = BuildConfig.DEBUG): CoreDef? {
        val candidates = cores.filter { platformSlug in it.platforms && (debug || !it.debugOnly) }
        return candidates.firstOrNull { it.id == debugChoice[platformSlug] } ?: candidates.firstOrNull { !it.optIn }
    }

    /** A core by its id; null for none. */
    fun byId(id: String?): CoreDef? = cores.firstOrNull { it.id == id }

    val platforms: Set<String> get() = cores.filter { (BuildConfig.DEBUG || !it.debugOnly) && !it.optIn }.flatMap { it.platforms }.toSet()
}
