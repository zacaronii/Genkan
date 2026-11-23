package app.genkan.dictionary

/**
 * Which way results are sorted by the chosen frequency dictionary: hoshidicts'
 * hd_lookup_frequency_order ([native]). [AUTO] there means no sort dictionary at all (Yomitan with
 * its sort frequency dictionary unset), so homophones fall to dictionary order and term score.
 */
enum class FrequencyOrder(val native: Int) {
    AUTO(0),
    ASCENDING(1),
    DESCENDING(2),
    DISABLED(3),
    ;

    companion object {
        /**
         * A linked Hachidori's own setting ([hostOrder], passed on as it is, as Hachidori's lookups
         * do); else by the dictionary's frequencyMode, as Yomitan and Hachidori read it: counts of
         * occurrences sort the highest first, ranks (or no mode, as most Japanese lists have) the
         * lowest first.
         */
        fun of(hostOrder: String?, mode: String?): FrequencyOrder = when (hostOrder) {
            "auto" -> AUTO
            "ascending" -> ASCENDING
            "descending" -> DESCENDING
            "disabled" -> DISABLED
            else -> if (mode == "occurrence-based") DESCENDING else ASCENDING
        }
    }
}
