package app.genkan.ppsspp

/** Minimal edits to an ini file such as ppsspp.ini, leaving every other line as it was. */
object Ini {
    private const val BOM = "\uFEFF"

    /** [key]'s value in [section], or null. */
    fun get(text: String, section: String, key: String): String? {
        var inSection = false
        for (line in text.lines()) {
            val trimmed = line.trim().removePrefix(BOM)
            if (trimmed.startsWith("[")) inSection = trimmed.equals("[$section]", ignoreCase = true)
            else if (inSection && trimmed.substringBefore('=').trim().equals(key, ignoreCase = true) && '=' in trimmed) {
                return trimmed.substringAfter('=').trim()
            }
        }
        return null
    }

    /** [text] with [key] in [section] set to [value]: replaced in place, or added to the section (made if missing). */
    fun set(text: String, section: String, key: String, value: String): String {
        val newline = if ("\r\n" in text) "\r\n" else "\n"
        val lines = text.split(newline).toMutableList()
        var sectionStart = -1
        var sectionEnd = lines.size
        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim().removePrefix(BOM)
            if (!trimmed.startsWith("[")) continue
            if (sectionStart >= 0) { sectionEnd = i; break }
            if (trimmed.equals("[$section]", ignoreCase = true)) sectionStart = i
        }
        val entry = "$key = $value"
        if (sectionStart < 0) {
            val body = text.trimEnd('\r', '\n')
            return (if (body.isEmpty()) "" else body + newline + newline) + "[$section]" + newline + entry + newline
        }
        for (i in sectionStart + 1 until sectionEnd) {
            val trimmed = lines[i].trim()
            if ('=' in trimmed && trimmed.substringBefore('=').trim().equals(key, ignoreCase = true)) {
                lines[i] = entry
                return lines.joinToString(newline)
            }
        }
        // After the section's last non-blank line.
        var at = sectionEnd
        while (at > sectionStart + 1 && lines[at - 1].isBlank()) at--
        lines.add(at, entry)
        return lines.joinToString(newline)
    }
}
