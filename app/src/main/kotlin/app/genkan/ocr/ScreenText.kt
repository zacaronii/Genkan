package app.genkan.ocr

import org.json.JSONArray
import org.json.JSONObject

/**
 * The text OCR last found on the top screen, each line where it sits as fractions of the frame:
 * what the overlay draws over the game, and where a tap on it lands. Goes to the player's
 * process as JSON.
 */
data class ScreenText(val lines: List<Line>) {
    /**
     * [vertical]: the line runs top to bottom (measured in pixels, before the fractions).
     * [ends]: each boxed character's offset in [text] and where it ends along the line, as a
     * fraction of the frame; empty when the engine boxes only lines.
     */
    data class Line(
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val vertical: Boolean = false,
        val ends: List<Pair<Int, Float>> = emptyList(),
    ) {
        /**
         * The box around characters [start] until [end] of [text], as fractions of the frame:
         * from the end of the character before [start] (the line's start for the first) to the end
         * of the last one, across the line's whole width. Without character boxes, an even share
         * of the line per character.
         */
        fun span(start: Int, end: Int): Box {
            val from = start.coerceIn(0, text.length)
            val to = end.coerceIn(from, text.length)
            val first = if (vertical) top else left
            val last = if (vertical) bottom else right
            fun edgeAfter(count: Int): Float = when {
                count <= 0 -> first
                ends.isNotEmpty() -> ends.lastOrNull { it.first < count }?.second ?: first
                else -> first + (last - first) * count / text.length.coerceAtLeast(1)
            }
            val a = edgeAfter(from)
            val b = if (to >= text.length) last else edgeAfter(to)
            return if (vertical) Box(left, a, right, b) else Box(a, top, b, bottom)
        }
    }

    /** A box as fractions of the frame. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** A tap on [text] at character [offset], on [line]. */
    data class Hit(val text: String, val offset: Int, val line: Line? = null)

    /**
     * The character at ([x], [y]), fractions of the frame, or null off every line: the first
     * boxed character ending past the tap. Without character boxes, Japanese text is near enough
     * to evenly spaced that the tap's share of the line's box is its share of the line.
     */
    fun hit(x: Float, y: Float): Hit? {
        val line = lines.firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom } ?: return null
        if (line.text.isEmpty()) return null
        val along = if (line.vertical) y else x
        if (line.ends.isNotEmpty()) {
            return Hit(line.text, (line.ends.firstOrNull { it.second >= along } ?: line.ends.last()).first, line)
        }
        val share = if (line.vertical) (y - line.top) / (line.bottom - line.top) else (x - line.left) / (line.right - line.left)
        val offset = (share * line.text.length).toInt().coerceIn(0, line.text.length - 1)
        return Hit(line.text, offset, line)
    }

    fun toJson(): String = JSONArray(
        lines.map { line ->
            JSONObject().put("text", line.text).put("l", line.left).put("t", line.top).put("r", line.right).put("b", line.bottom).put("v", line.vertical)
                .put("o", JSONArray(line.ends.map { it.first })).put("e", JSONArray(line.ends.map { it.second }))
        },
    ).toString()

    companion object {
        val EMPTY = ScreenText(emptyList())

        /** Lines read from a crop at ([cropLeft], [cropTop]) of a [frameWidth] by [frameHeight] frame. */
        fun of(lines: List<OcrLine>, cropLeft: Int, cropTop: Int, frameWidth: Int, frameHeight: Int): ScreenText {
            if (frameWidth <= 0 || frameHeight <= 0) return EMPTY
            val w = frameWidth.toFloat()
            val h = frameHeight.toFloat()
            return ScreenText(
                lines.filter { it.text.isNotBlank() && it.width > 0 && it.height > 0 }.map {
                    val vertical = it.vertical
                    // Offsets in the trimmed text, ends in the frame.
                    val lead = it.text.length - it.text.trimStart().length
                    val text = it.text.trim()
                    Line(
                        text,
                        (cropLeft + it.left) / w,
                        (cropTop + it.top) / h,
                        (cropLeft + it.left + it.width) / w,
                        (cropTop + it.top + it.height) / h,
                        vertical,
                        it.ends.filter { e -> e.offset - lead in text.indices }.map { e ->
                            (e.offset - lead) to if (vertical) (cropTop + e.end) / h else (cropLeft + e.end) / w
                        },
                    )
                },
            )
        }

        fun fromJson(json: String): ScreenText {
            if (json.isBlank()) return EMPTY
            val a = JSONArray(json)
            return ScreenText(
                List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    val offsets = o.optJSONArray("o") ?: JSONArray()
                    val ends = o.optJSONArray("e") ?: JSONArray()
                    Line(
                        o.getString("text"), o.getDouble("l").toFloat(), o.getDouble("t").toFloat(), o.getDouble("r").toFloat(), o.getDouble("b").toFloat(), o.optBoolean("v"),
                        List(minOf(offsets.length(), ends.length())) { j -> offsets.getInt(j) to ends.getDouble(j).toFloat() },
                    )
                },
            )
        }
    }
}
