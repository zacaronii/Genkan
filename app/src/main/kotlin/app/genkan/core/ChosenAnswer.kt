package app.genkan.core

import app.genkan.yomikata.LayoutRead

/**
 * The answer a player took at a layout's choice, for the backlog: the row the cursor was on when
 * the choice closed. Only when that row is known: a cursor the layout doesn't read, or one still
 * at -1 (no row picked) when the choice goes, gives nothing, as does a choice that ends because
 * the layout stopped.
 */
class ChosenAnswer {
    /** An answer taken at a choice, and its question's line as the backlog has it (rows joined), if any. */
    data class Taken(val answer: String, val question: String?)

    private var answers: List<String> = emptyList()
    private var cursor: Long? = null
    private var question: String? = null

    /** The answer taken, when [read] is the first read after a choice closed; else null. */
    fun read(read: LayoutRead): Taken? {
        if (read.asking) {
            answers = read.choices.orEmpty()
            cursor = read.choiceCursor
            question = read.message?.let { LayoutLines.withoutAnswers(it, answers) }?.replace("\n", "")?.takeIf { it.isNotBlank() }
            return null
        }
        if (answers.isEmpty()) return null
        val taken = cursor?.toInt()?.takeIf { it in answers.indices }?.let { answers[it].trim() }?.takeIf { it.isNotEmpty() }
        val asked = question
        reset()
        return taken?.let { Taken(it, asked) }
    }

    /** The layout stopped, or a new game: whatever choice was up gives no answer. */
    fun reset() {
        answers = emptyList()
        cursor = null
        question = null
    }

    companion object {
        /** How an answer reads in the backlog: marked as the player's, after the question. */
        fun line(answer: String): String = "→ $answer"
    }
}

/**
 * A layout's reads as backlog lines: [LayoutLines]' lines, and after a choice the answer taken
 * ([ChosenAnswer]), right after its question. On the read the choice closes on, the answer goes
 * after the question when that read sends it, and otherwise before anything else the read sends
 * (the next line, or part of it): those are newer than the choice. A choice can also close (a
 * quick answer) while its question is still the current line, unsent, the page up for a frame or
 * two: the question goes first then.
 */
class LayoutBacklog {
    val lines = LayoutLines()
    private val chosen = ChosenAnswer()

    fun read(read: LayoutRead): LayoutLines.Step {
        val step = lines.read(read)
        val taken = chosen.read(read) ?: return step
        val answer = LayoutLines.Line(ChosenAnswer.line(taken.answer), speaker = null)
        val asked = step.lines.indexOfLast { it.text == taken.question }
        if (asked >= 0) return step.copy(lines = step.lines.toMutableList().apply { add(asked + 1, answer) })
        // The question went earlier, or is still the current line and goes now.
        val question = taken.question?.let { lines.sendIfCurrent(it) }
        return step.copy(lines = listOfNotNull(question, answer) + step.lines)
    }

    fun settled(): LayoutLines.Line? = lines.settled()

    fun reset() {
        lines.reset()
        chosen.reset()
    }
}
