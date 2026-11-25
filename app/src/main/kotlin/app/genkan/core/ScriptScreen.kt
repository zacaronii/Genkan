package app.genkan.core

import app.genkan.ocr.ScreenText

/**
 * Where a script says its texts are drawn, as the overlay's boxes over the game: each window's
 * cells, in the layout's own space, put onto the game's picture (where it sits on the view, without
 * the bars around it), a box per row of characters. A tap on a row looks up from that character
 * through the window's whole text, so a word broken across rows is still one word.
 */
object ScriptScreen {

    /**
     * [layouts] on a picture at [bounds] (left, top, right, bottom, as fractions of the view). Only
     * the upper screen's: a DS's lower one isn't under this overlay.
     */
    fun of(layouts: List<ScriptReader.WindowLayout>, bounds: FloatArray): ScreenText {
        val (left, top, right, bottom) = bounds.toList()
        val lines = layouts.filter { it.screen == 0 && it.width > 0 && it.height > 0 }.flatMap { layout ->
            fun x(v: Float) = left + v / layout.width * (right - left)
            fun y(v: Float) = top + v / layout.height * (bottom - top)
            rows(layout).map { row ->
                val cells = row.map { it.second }
                ScreenText.Line(
                    text = layout.text,
                    left = x(cells.minOf { it[0] }),
                    top = y(cells.minOf { it[1] }),
                    right = x(cells.maxOf { it[0] + it[2] }),
                    bottom = y(cells.maxOf { it[1] + it[3] }),
                    ends = row.map { (offset, cell) -> offset to x(cell[0] + cell[2]) },
                )
            }
        }
        return ScreenText(lines)
    }

    /**
     * The layout's cells by row, each with its character's UTF-16 offset in the text: a row ends at
     * a cell with nothing drawn (a line break), or where the pen goes back to the left or down a line.
     */
    internal fun rows(layout: ScriptReader.WindowLayout): List<List<Pair<Int, FloatArray>>> {
        val rows = mutableListOf<MutableList<Pair<Int, FloatArray>>>()
        var current = mutableListOf<Pair<Int, FloatArray>>()
        var offset = 0
        var index = 0
        while (offset < layout.text.length && index < layout.glyphs.size) {
            val cell = layout.glyphs[index]
            val last = current.lastOrNull()?.second
            if (cell == null || (last != null && (cell[0] < last[0] || cell[1] >= last[1] + last[3] / 2))) {
                if (current.isNotEmpty()) rows += current
                current = mutableListOf()
            }
            if (cell != null) current += offset to cell
            offset += Character.charCount(layout.text.codePointAt(offset))
            index++
        }
        if (current.isNotEmpty()) rows += current
        return rows
    }
}
