package app.genkan.core

import app.genkan.yomikata.LayoutRead

/**
 * The answer a player took at a layout's choice, for the backlog: the row the cursor was on when
 * the choice closed. Only when that row is known: a cursor the layout doesn't read, or one still
 * at -1 (no row picked) when the choice goes, gives nothing, as does a choice that ends because
 * the layout stopped.
 */
class ChosenAnswer {
    private var answers: List<String> = emptyList()
    private var cursor: Long? = null

    /** The answer taken, when [read] is the first read after a choice closed; else null. */
    fun read(read: LayoutRead): String? {
        if (read.asking) {
            answers = read.choices.orEmpty()
            cursor = read.choiceCursor
            return null
        }
        if (answers.isEmpty()) return null
        val taken = cursor?.toInt()?.takeIf { it in answers.indices }?.let { answers[it].trim() }?.takeIf { it.isNotEmpty() }
        reset()
        return taken
    }

    /** The layout stopped, or a new game: whatever choice was up gives no answer. */
    fun reset() {
        answers = emptyList()
        cursor = null
    }

    companion object {
        /** How an answer reads in the backlog: marked as the player's, after the question. */
        fun line(answer: String): String = "→ $answer"
    }
}
