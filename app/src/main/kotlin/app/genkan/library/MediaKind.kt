package app.genkan.library

/** What a platform's games physically came on, for drawing the right case and cut-out. */
enum class MediaKind {
    CARTRIDGE,
    DISC,
    /** The PSP's Universal Media Disc. */
    UMD,
    /** The Famicom Disk System's disk cards. */
    DISK_CARD,
    /** The DS and 3DS game cards, and their kin; kept apart from cartridges for their small cases. */
    CARD,
    UNKNOWN;

    companion object {
        /**
         * RomM's platform slugs, by media. A platform with more than one slug in use (RomM's own
         * and the folder names people give it) lists each. Anything not here is [UNKNOWN].
         */
        private val BY_SLUG: Map<String, MediaKind> = buildMap {
            fun put(kind: MediaKind, vararg slugs: String) = slugs.forEach { put(it, kind) }
            put(
                CARTRIDGE,
                "nes", "famicom", "fc", "snes", "sfc", "sfam", "n64", "gb", "gbc", "gba",
                "virtualboy", "pokemon-mini", "genesis", "megadrive", "md", "sms", "gamegear", "gg",
                "sega32", "neo-geo-pocket", "neo-geo-pocket-color", "ngp", "ngpc",
                "wonderswan", "wonderswan-color", "ws", "wsc", "atari2600", "atari5200", "atari7800",
                "lynx", "jaguar",
            )
            put(
                DISC,
                "psx", "ps1", "ps2", "ps3", "ngc", "gc", "wii", "wiiu", "saturn", "dc", "dreamcast",
                "segacd", "3do", "turbografx-cd", "pc-fx", "neo-geo-cd", "xbox", "xbox360", "philips-cd-i",
            )
            put(UMD, "psp")
            put(DISK_CARD, "fds")
            put(CARD, "nds", "3ds", "n3ds", "new-nintendo-3ds", "psvita", "switch")
        }

        /** The media for a RomM platform slug, case-insensitively. */
        fun forSlug(slug: String?): MediaKind = slug?.let { BY_SLUG[it.lowercase()] } ?: UNKNOWN
    }
}
