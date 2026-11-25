package app.genkan.reader

import org.json.JSONArray

/**
 * A game's own furigana on a backlog line: [reading] over the characters from [start] until [end]
 * (UTF-16 offsets in the line's text), as a Yomikata script gives them.
 */
data class Ruby(val start: Int, val end: Int, val reading: String) {
    companion object {
        /** As stored with a backlog line: `[[start, end, "reading"], …]`; null for none. */
        fun toJson(ruby: List<Ruby>): String? =
            if (ruby.isEmpty()) null else JSONArray(ruby.map { JSONArray().put(it.start).put(it.end).put(it.reading) }).toString()

        fun fromJson(json: String?): List<Ruby> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).map { i -> array.getJSONArray(i).let { Ruby(it.getInt(0), it.getInt(1), it.getString(2)) } }
            }.getOrDefault(emptyList())
        }

        /**
         * [ruby] on [original] moved onto [normalised], which is [original] with its spaces and line
         * breaks collapsed or taken out (Lines.normalise) and every other character kept in order.
         * One over characters that didn't survive is dropped.
         */
        fun remap(ruby: List<Ruby>, original: String, normalised: String): List<Ruby> {
            if (ruby.isEmpty()) return ruby
            val at = IntArray(original.length) { -1 }
            var j = 0
            for (i in original.indices) {
                val c = original[i]
                if (c.isWhitespace()) continue
                while (j < normalised.length && normalised[j] != c) j++
                if (j == normalised.length) break
                at[i] = j++
            }
            return ruby.mapNotNull { r ->
                if (r.start !in original.indices || r.end !in r.start + 1..original.length) return@mapNotNull null
                val start = at[r.start]
                val last = at[r.end - 1]
                if (start < 0 || last < start) null else Ruby(start, last + 1, r.reading)
            }
        }

        /** A script's ruby (`[[start, length, "reading"], …]` in code points of [text]) in UTF-16 offsets. */
        fun fromCodePoints(text: String, ruby: JSONArray?): List<Ruby> {
            if (ruby == null || ruby.length() == 0) return emptyList()
            return (0 until ruby.length()).mapNotNull { i ->
                val r = ruby.optJSONArray(i) ?: return@mapNotNull null
                val first = r.optInt(0, -1)
                val count = r.optInt(1, 0)
                val reading = r.optString(2)
                val points = text.codePointCount(0, text.length)
                if (first < 0 || count <= 0 || first + count > points || reading.isEmpty()) return@mapNotNull null
                val start = text.offsetByCodePoints(0, first)
                Ruby(start, text.offsetByCodePoints(start, count), reading)
            }
        }
    }
}
