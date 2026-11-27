package app.genkan.ui

/**
 * What Settings › Advanced › Save a log leaves out of the log it writes: the [secrets] Genkan holds
 * (the RomM token, the servers' keys), wherever they appear, and anything a log line gives as a key,
 * a token or an Authorization header.
 */
object LogRedaction {
    const val REDACTED = "[redacted]"

    private val patterns = listOf(
        Regex("""(?i)(authorization\s*[:=]\s*)(?:bearer\s+|basic\s+|token\s+)?\S+"""),
        Regex("""(?i)\b((?:api[_-]?key|apikey|token|access[_-]?token|password|secret)\s*[=:]\s*)["']?[^\s"'&,;]+"""),
        Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/=-]+"""),
    )

    fun redact(text: String, secrets: Collection<String>): String {
        var out = text
        // Longest first, so a secret that holds another is taken whole.
        secrets.filter { it.length >= MIN_SECRET }.sortedByDescending { it.length }.forEach { out = out.replace(it, REDACTED) }
        patterns.forEach { pattern -> out = pattern.replace(out) { "${it.groupValues[1]}$REDACTED" } }
        return out
    }

    /** Shorter values aren't treated as secrets: they'd blank out ordinary words. */
    private const val MIN_SECRET = 6
}
