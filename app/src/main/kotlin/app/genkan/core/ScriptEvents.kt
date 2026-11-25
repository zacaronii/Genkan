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

    fun kindName(kind: Int): String = when (kind) {
        TEXT -> "text"
        LAYOUT -> "layout"
        CURSOR -> "cursor"
        LOG -> "log"
        else -> "kind $kind"
    }

    private val CATALOG_PLATFORMS = mapOf("ngc" to "gc", "psx" to "ps1", "snes" to "sfc", "sfam" to "sfc")
}
