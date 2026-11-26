package app.genkan.core

/**
 * The names a Yomikata script's runtime uses: the catalog's platform names (where RomM's slugs
 * differ), and the kinds of event it hands back (yomikata_reader.h's YK_TEXT …).
 */
object ScriptEvents {
    const val TEXT = 1
    const val LAYOUT = 2
    const val CURSOR = 3
    const val LOG = 4

    /** The catalog's name for RomM's platform [slug]: `gc` for `ngc`, `ps1` for `psx`, `sfc` for the Super Famicom. */
    fun catalogPlatform(slug: String): String = CATALOG_PLATFORMS[slug] ?: slug

    /**
     * The game's build as scripts name it, where Genkan can tell: a GameCube or Wii disc's id and version
     * from its RAM (`XXXXXX-00`), a cartridge's header CRC-32 (`1a2b3c4d`, as CartridgeId reads
     * it); null otherwise, and the script's own check decides.
     */
    fun buildId(platform: String?, ram: ByteArray?, cartridgeCrc: String?): String? = when {
        platform == "ngc" -> ram?.let { app.genkan.memory.RamSnapshots.gcBuildId(it) }
        platform == "wii" -> ram?.let { app.genkan.memory.RamSnapshots.wiiBuildId(it) }
        cartridgeCrc != null -> cartridgeCrc.lowercase()
        else -> null
    }

    fun kindName(kind: Int): String = when (kind) {
        TEXT -> "text"
        LAYOUT -> "layout"
        CURSOR -> "cursor"
        LOG -> "log"
        else -> "kind $kind"
    }

    private val CATALOG_PLATFORMS = mapOf("ngc" to "gc", "psx" to "ps1", "snes" to "sfc", "sfam" to "sfc")
}
