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
 * Where an [OverlayPanel]'s rows sit, in the overlay's pixels: a column of [rowHeight] rows from
 * ([left], [top]), [width] wide. Plain numbers, so a tap can be placed without drawing.
 */
data class PanelGeometry(val left: Float, val top: Float, val width: Float, val rowHeight: Float, val rows: Int) {
    val bottom: Float get() = top + rowHeight * rows

    /** The row at ([x], [y]), or null outside the panel. */
    fun rowAt(x: Float, y: Float): Int? {
        if (x < left || x > left + width || y < top || y >= bottom) return null
        return ((y - top) / rowHeight).toInt().coerceIn(0, rows - 1)
    }

    companion object {
        /**
         * The panel for [rows] rows in a [viewWidth] by [viewHeight] overlay: in the top right,
         * [widthFraction] of the width, inset by [margin]; rows shrink to fit the height.
         */
        fun place(rows: Int, viewWidth: Float, viewHeight: Float, rowHeight: Float, margin: Float, widthFraction: Float = 0.38f): PanelGeometry {
            val width = viewWidth * widthFraction
            val fit = ((viewHeight - 2 * margin) / rows.coerceAtLeast(1)).coerceAtMost(rowHeight)
            return PanelGeometry(viewWidth - margin - width, margin, width, fit, rows)
        }
    }
}
