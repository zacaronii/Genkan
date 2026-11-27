package app.genkan.core

import app.genkan.BuildConfig

/** A stretch of a core's system RAM, [length] bytes from [offset], that the console sees at [address]. */
data class RamStretch(val address: Long, val offset: Long, val length: Long)

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
     * For a core that gives no memory map and whose system RAM is more than one of the console's
     * stretches (a Saturn's work RAM): each, at the address the console sees it at.
     */
    val ramStretches: List<RamStretch> = emptyList(),
    /**
     * The core keeps its memory (that RAM, or the blocks of its memory map) as words of this many
     * bytes in the host's order, where the console has them big-endian: 2, reads swap each byte pair
     * back (a Saturn's); 4, they reverse each word's four bytes (an N64's). 0: as it is.
     */
    val ramWordSize: Int = 0,
    /**
     * The same for the core's video RAM: Genesis Plus GX keeps the VDP's as 16-bit words in the host's
     * order. Reads (snapshots, scripts) give it in the console's, as Yomikata's harness does. 0: as it is.
     */
    val videoRamWordSize: Int = 0,
    /**
     * Snapshots and the text search read the core's blocks whole where its map leaves gaps in them
     * (a PC Engine CD's card RAM), as Yomikata's harness does; scripts keep to the map.
     */
    val readsWhole: Boolean = false,
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
     * The core's sound plays through the low-latency stream when Settings allows (a buffer of four
     * frames); false for one that makes its sound in bigger lumps, or whose frames take long now and
     * then, either of which runs that buffer dry (a gap of silence): it gets the plain stream's eight.
     */
    val lowLatencySound: Boolean = true,
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
    /**
     * The core can draw with Vulkan (libretrodroid's vulkanbridge.h), and is offered it on a ladder
     * step that asks for it (TuningStep.vulkan); every other core is never offered it.
     */
    val vulkan: Boolean = false,
    /** A lifted finger leaves the game's pointer where it was (a Wii's: aimed, then left there). */
    val keepPointer: Boolean = false,
    /**
     * Only in debug builds, whose library holds it: one of two cores tried side by side for a
     * console until a round on the device picks one (see [CoreCatalog.choice]).
     */
    val debugOnly: Boolean = false,
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
            // The card's 192 KB is one block of the core's, whose map gives its first and third 64 KB
            // at their places and not the middle: a CD game's snapshot has it all, as Yomikata's.
            readsWhole = true,
            extensions = setOf("pce", "cue", "ccd", "chd", "toc", "m3u"),
        ),
        CoreDef(
            // Mednafen's: the most accurate, its SH-2s interpreted. Full speed on the Thor with half the
            // CPU spare (YabaSanshiro, the other candidate, drew no picture here). It boots with the
            // Japanese BIOS from RomM's Saturn firmware.
            id = "beetle_saturn", displayName = "Beetle Saturn", library = "libmednafen_saturn_libretro_android.so",
            platforms = setOf("saturn"),
            firmware = mapOf("sega_101.bin" to "sega_101.bin"),
            // No memory map: its system RAM is the work RAM, the low 1 MB and then the high, as 16-bit
            // words in the host's order. Read at the console's addresses and in its order, as
            // Yomikata's harness does; games point at both past the cache too, at 0x20000000 on.
            ramStretches = listOf(RamStretch(0x06000000L, 0x100000L, 0x100000L), RamStretch(0x00200000L, 0L, 0x100000L)),
            ramWordSize = 2,
            pointerMask = 0xDFFFFFFFL,
            bigEndian = true,
            // Not zip, which the core reads as an arcade board's: a zipped disc is unpacked first.
            extensions = setOf("cue", "ccd", "chd", "toc", "m3u"),
        ),
        CoreDef(
            // Genesis Plus GX, Lemuroid's build: Mega Drive cartridges (RomM's genesis and its other names).
            id = "genesis_plus_gx", displayName = "Genesis Plus GX", library = "libgenesis_plus_gx_libretro_android.so",
            platforms = setOf("genesis", "genesis-slash-megadrive", "megadrive"),
            // No memory map: its system RAM is the 68000's 64 KB of work RAM, as 16-bit words in the
            // host's order. Read at the console's address and in its order, as Yomikata's harness does;
            // its addresses are 24-bit, the RAM mirrored up to 0xFFFFFF.
            ramStretches = listOf(RamStretch(0xFF0000L, 0L, 0x10000L)),
            ramWordSize = 2,
            // Its VDP's 64 KB too (patches/genesis_plus_gx), 16-bit words in the host's order.
            videoRamWordSize = 2,
            pointerMask = 0xFFFFFFL,
            bigEndian = true,
            extensions = setOf("md", "gen", "smd", "bin"),
        ),
        CoreDef(
            // FCEUmm, Lemuroid's build: Famicom cartridges, and Famicom Disk System disks, which boot
            // through the Disk System's BIOS from RomM's FDS firmware.
            id = "fceumm", displayName = "FCEUmm", library = "libfceumm_libretro_android.so",
            platforms = setOf("nes", "famicom", "fds"),
            firmware = mapOf("disksys.rom" to "disksys.rom"),
            extensions = setOf("nes", "fds", "unf", "unif"),
        ),
        CoreDef(
            // Mupen64Plus-Next with GLideN64 on OpenGL ES 3, Lemuroid's build.
            id = "mupen64plus_next", displayName = "Mupen64Plus-Next", library = "libmupen64plus_next_gles3_libretro_android.so",
            platforms = setOf("n64"),
            // Its memory map gives the RDRAM (8 MB, with the Expansion Pak's 4) at 0x80000000, the RSP's
            // memory and the cartridge, all as 32-bit words in the host's order: read in the console's
            // order, as Yomikata's harness does. Games point at the RDRAM past the cache too, at 0xA0000000.
            ramWordSize = 4,
            pointerMask = 0x9FFFFFFFL,
            bigEndian = true,
            // Its analog stick is the console's own: never also the D-pad.
            sticksOnly = true,
            extensions = setOf("n64", "v64", "z64"),
        ),
        CoreDef(
            id = "flycast", displayName = "Flycast", library = "libflycast_libretro_android.so",
            platforms = setOf("dc"),
            variables = mapOf(
                // A Japanese console: its BIOS's language and the region discs see.
                "reicast_region" to "Japan",
                "reicast_language" to "Japanese",
                // Each game's own VMU in port A1, named by its product number, in the saves folder;
                // the core's default is one VMU for every game, in its system folder.
                "reicast_per_content_vmus" to "VMU A1",
            ),
            // The BIOS and its flash from RomM's Dreamcast firmware, in the dc folder the core looks in.
            firmware = mapOf("dc_boot.bin" to "dc/dc_boot.bin", "dc_flash.bin" to "dc/dc_flash.bin"),
            // The core gives its 16 MB of RAM without a map: games see it at 0x0C000000, and point at
            // it through the 0x8C000000 and 0xAC000000 mirrors.
            pointerMask = 0x1FFFFFFFL,
            ramBase = 0x0C000000L,
            // Not zip or 7z, which the core reads as an arcade board's: a zipped disc is unpacked first.
            extensions = setOf("chd", "cdi", "gdi", "cue", "m3u"),
            // On shared storage, where the save sync watches it (WatchedEmulator.BUILT_IN).
            savesFolder = "Genkan/saves/flycast",
            // A Dreamcast controller in port 1: with the core's own default the game read no button.
            portDevices = listOf(RETRO_DEVICE_JOYPAD),
            // Its emulation runs on a thread of its own: a game drawing 30 frames a second makes two
            // frames of sound a run, every other display frame (libretrodroid's audiopace.h).
            lowLatencySound = false,
        ),
        CoreDef(
            // libretro's PCSX2 with native ARM64 recompilers, built from source. The PS2's own
            // player unless ARMSX2 is chosen for it (Settings, CoreCatalog.choice).
            id = "lrps2", displayName = "LRPS2", library = "libpcsx2_libretro_android.so",
            platforms = setOf("ps2"),
            // The renderer is its ladder's (TuningLadders): "Auto", the frontend's preferred context
            // (Vulkan on the Vulkan step, else OpenGL ES 3, for which the core's GL renderer asks 3.1;
            // "OpenGL" asks for desktop GL 3.3 and draws nothing), or Software on 6 threads.
            variables = mapOf("pcsx2_bios" to "ps2-0230a-20080220.bin"),
            // RomM's PS2 BIOS, in the folder the core looks in.
            firmware = mapOf("ps2-0230a-20080220.bin" to "pcsx2/bios/ps2-0230a-20080220.bin"),
            // The core gives the EE's 32 MB without a map, from address 0; games also point at it
            // through the uncached views at 0x20000000 and 0x30000000.
            pointerMask = 0x01FFFFFFL,
            extensions = setOf("iso", "chd", "cso", "zso", "ciso", "cue", "elf", "m3u"),
            // Its Vulkan renderer, on the ladder's Vulkan step only.
            vulkan = true,
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
            // A frame of 20 to 30 ms now and then emptied the four-frame buffer: gaps of silence.
            lowLatencySound = false,
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
                // The Wiimote's sweep across the screen, sized so the cursor stays under the finger:
                // the core's 25° each way took it twice the finger's travel up and down. Measured
                // on the Thor; 13 is under the core's listed 15, but it reads any number.
                "dolphin_ir_yaw" to "24", "dolphin_ir_pitch" to "13",
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
            lowLatencySound = false,
            portDevices = listOf(RETRO_DEVICE_JOYPAD),
            sticksOnly = true,
            tuning = "dolphin",
            keepPointer = true,
        ),
    )

    /** [choice]'s value for a console played by its emulator app, not the built-in player. */
    const val EXTERNAL = "external"

    /**
     * What plays each console, by platform, as Settings keeps it (PlayerSettings.coreChoice, mirrored
     * here in the launcher's process): one of its cores' ids, or [EXTERNAL]. Absent, its first core.
     */
    @Volatile var choice: Map<String, String> = emptyMap()

    /** The cores for RomM's platform [platformSlug] in this build; [debug]: whether debug-only ones count. */
    fun coresFor(platformSlug: String, debug: Boolean = BuildConfig.DEBUG): List<CoreDef> =
        cores.filter { platformSlug in it.platforms && (debug || !it.debugOnly) }

    /**
     * The core that plays RomM's platform [platformSlug]: the chosen one, else its first; null
     * when it has none, or its emulator app is chosen. [debug]: whether debug-only cores count.
     */
    fun forPlatform(platformSlug: String, debug: Boolean = BuildConfig.DEBUG): CoreDef? {
        val chosen = choice[platformSlug]
        if (chosen == EXTERNAL) return null
        val candidates = coresFor(platformSlug, debug)
        return candidates.firstOrNull { it.id == chosen } ?: candidates.firstOrNull()
    }

    /** A core by its id; null for none. */
    fun byId(id: String?): CoreDef? = cores.firstOrNull { it.id == id }

    val platforms: Set<String> get() = cores.filter { BuildConfig.DEBUG || !it.debugOnly }.flatMap { it.platforms }.toSet()
}
