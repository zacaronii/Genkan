package app.genkan.art

/**
 * A RomM artwork file, by its server-relative [path] (as the library keeps it, with RomM's `ts`
 * version on it): what the screens hand Coil instead of a URL. The URL is made when it loads,
 * from the server signed in to then; the cache knows it by [key], which stays the same for the
 * same file across syncs (a new `ts` for any change to the game) and server addresses. A file
 * replaced on the server is found by the daily check ([ArtCacheStrategy]); [version] goes up when
 * it was, so the screen loads it again.
 */
data class RomMArt(val path: String, val version: Int = 0) {
    val key: String get() = keyOf(path)

    companion object {
        /** [path] as art, or null without one (or for an address that isn't RomM's, like Steam art). */
        fun of(path: String?, version: Int = 0): RomMArt? =
            path?.trim()?.takeIf { it.isNotEmpty() && "://" !in it }?.let { RomMArt(it, version) }

        /** The cache key for [path]: the path without its `ts` query parameter and without a leading slash. */
        fun keyOf(path: String): String {
            val trimmed = path.trim().trimStart('/')
            val query = trimmed.substringAfter('?', "")
            val base = trimmed.substringBefore('?')
            val kept = query.split('&').filter { it.isNotEmpty() && !it.startsWith("ts=") }
            return "romm-art:" + if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
        }
    }
}
