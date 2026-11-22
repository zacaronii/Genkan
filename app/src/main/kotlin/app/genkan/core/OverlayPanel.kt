package app.genkan.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A list drawn over the game in the built-in player, its rows tappable to look up, apart from the
 * backlog: what doesn't belong in the backlog (text nobody wants kept) but is worth reading.
 * A layout's choices are the first; a game's menus (an inventory, say) can be another [Kind].
 * [highlighted] is the row the game's cursor is on, or null when none is (a choice's cursor
 * before the first move reads -1).
 */
data class OverlayPanel(val kind: Kind, val items: List<String>, val highlighted: Int? = null) {

    enum class Kind {
        /** A choice's answers, from a layout's `choices`. */
        CHOICE,

        /** A game's menu: none produced yet. */
        MENU,
    }

    fun toJson(): String = JSONObject()
        .put("kind", kind.name)
        .put("items", JSONArray(items))
        .apply { highlighted?.let { put("highlighted", it) } }
        .toString()

    companion object {
        /** The panel in [json], or null for none (blank) or one this build can't read. */
        fun fromJson(json: String): OverlayPanel? {
            if (json.isBlank()) return null
            return runCatching {
                val o = JSONObject(json)
                val items = o.getJSONArray("items").let { a -> List(a.length()) { a.getString(it) } }
                val kind = Kind.entries.firstOrNull { it.name == o.optString("kind") } ?: return null
                val highlighted = if (o.has("highlighted")) o.getInt("highlighted") else null
                OverlayPanel(kind, items, highlighted?.takeIf { it in items.indices })
            }.getOrNull()?.takeIf { it.items.isNotEmpty() }
        }

        /** The panel for a layout's [choice], or null when no choice is up. */
        fun of(choice: BuiltInText.Choice?): OverlayPanel? =
            choice?.takeIf { it.answers.isNotEmpty() }?.let { OverlayPanel(Kind.CHOICE, it.answers, it.cursor?.takeIf { c -> c >= 0 }) }
    }
}

/**
 * Where an [OverlayPanel]'s rows sit, in the overlay's pixels: a box from ([left], [top]),
 * [width] wide, its rows one under another, each row on as many lines of [lineHeight] as its text
 * needs ([rows]: each row's first line's index and where each of its lines starts in its text).
 * Plain numbers, so a tap can be placed without drawing.
 */
data class PanelGeometry(
    val left: Float,
    val top: Float,
    val width: Float,
    val lineHeight: Float,
    /** [pad] in from the box's left edge, where each line's text starts. */
    val pad: Float,
    val rows: List<Row>,
) {
    /** A row's lines: [starts] are where each begins in the row's text; [firstLine] counts from the panel's top. */
    data class Row(val firstLine: Int, val starts: List<Int>)

    val lines: Int get() = rows.sumOf { it.starts.size }
    val bottom: Float get() = top + lineHeight * lines

    /** The row and its line at ([x], [y]), or null outside the panel. */
    fun lineAt(x: Float, y: Float): Pair<Int, Int>? {
        if (x < left || x > left + width || y < top || y >= bottom) return null
        val line = ((y - top) / lineHeight).toInt().coerceIn(0, lines - 1)
        val row = rows.indexOfLast { it.firstLine <= line }
        return row to (line - rows[row].firstLine)
    }

    /**
     * The character of [text] (row [row]'s) under [x] on its line [line]: the line's start, plus
     * as many characters as [fit] says fit from there in the distance from the text's left.
     */
    fun offsetAt(text: String, row: Int, line: Int, x: Float, fit: (String, Int, Float) -> Int): Int {
        val start = rows[row].starts[line]
        val end = rows[row].starts.getOrNull(line + 1) ?: text.length
        val from = x - left - pad
        if (from <= 0f) return start
        return (start + fit(text, start, from)).coerceAtMost(end - 1).coerceAtLeast(start)
    }

    companion object {
        /** The narrowest the panel is, as a share of the overlay's width; it widens for longer rows. */
        const val MIN_WIDTH = 0.38f

        /** The widest it gets; a row longer than that wraps. */
        const val MAX_WIDTH = 0.60f

        /**
         * The panel for [items] in a [viewWidth] by [viewHeight] overlay, in the top right, inset by
         * [margin]: as wide as its longest row ([measure]) plus [pad] each side, from [MIN_WIDTH] to
         * [MAX_WIDTH] of the width; a row that still doesn't fit is wrapped where [fit] (how many of
         * a text's characters from an index fit in a width) says. Lines shrink to fit the height.
         */
        fun place(
            items: List<String>,
            viewWidth: Float,
            viewHeight: Float,
            lineHeight: Float,
            margin: Float,
            pad: Float,
            measure: (String) -> Float,
            fit: (String, Int, Float) -> Int,
        ): PanelGeometry {
            val longest = items.maxOfOrNull(measure) ?: 0f
            val width = (longest + 2 * pad).coerceIn(viewWidth * MIN_WIDTH, viewWidth * MAX_WIDTH)
            var line = 0
            val rows = items.map { text ->
                val starts = mutableListOf(0)
                var at = 0
                while (true) {
                    val n = fit(text, at, width - 2 * pad).coerceAtLeast(1)
                    if (at + n >= text.length) break
                    at += n
                    starts += at
                }
                PanelGeometry.Row(line, starts).also { line += starts.size }
            }
            val fitted = ((viewHeight - 2 * margin) / line.coerceAtLeast(1)).coerceAtMost(lineHeight)
            return PanelGeometry(viewWidth - margin - width, margin, width, fitted, pad, rows)
        }
    }
}
