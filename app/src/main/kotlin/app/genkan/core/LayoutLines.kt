package app.genkan.core

import app.genkan.yomikata.LayoutRead

/**
 * Which of a layout's reads make lines for the backlog. A line goes when it's complete: the game
 * waits for a press (`waiting`), or (without that flag) it has typed out in full (`shown`), or
 * (with neither) it stops changing ([Step.settle]). A line that never counts as complete still
 * goes when it's replaced by one that doesn't continue it, or the message clears: a game that
 * advances by itself, or a line skipped before it finished typing. Each line goes once, so the
 * same sentence twice in a row is two lines; a line that grows after it went (a box that adds a
 * sentence and waits again) goes again, taking back what went before. A game that waits for a
 * press partway through typing a message (`waiting` with `shown` short of the end) has shown only
 * that much: that much goes, and the rest follows as the line grows.
 */
class LayoutLines {
    /** [takesBack]: the text of this line that went before, which this one replaces. */
    data class Line(val text: String, val speaker: String?, val takesBack: String? = null)

    /** Lines to put in the backlog now; [settle]: the current line goes if nothing changes for a while. */
    data class Step(val lines: List<Line>, val settle: Boolean)

    private var current: Line? = null
    private var shown: Long? = null
    /** What of the current line has gone, or null. */
    private var sentText: String? = null

    /** The current line as it should go now, or null when all of it has gone. */
    private fun unsent(): Line? {
        val line = current ?: return null
        val gone = sentText
        if (gone == line.text) return null
        return line.copy(takesBack = gone)
    }

    /** The game waits with [shown] of the current line on screen: that much goes, once. */
    private fun sendPart(shown: String, out: MutableList<Line>) {
        val line = current ?: return
        val gone = sentText
        if (shown.isBlank() || shown == gone || (gone != null && gone.startsWith(shown))) return
        out += line.copy(text = shown, takesBack = gone)
        sentText = shown
    }

    private fun send(out: MutableList<Line>) {
        unsent()?.let {
            out += it
            sentText = it.text
        }
    }

    fun read(read: LayoutRead): Step {
        // A message with a character no script has (U+FFFD, a control other than a row break)
        // is memory that isn't the message: nothing is shown then. While a choice is up its
        // answers are shown as a list of their own, not as a line: only the question goes.
        val message = if (read.asking) read.message?.let { withoutAnswers(it, read.choices.orEmpty()) } else read.message
        val text = message?.takeIf { shown(it) }?.replace("\n", "")?.takeIf { it.isNotBlank() }
        val speaker = read.speakerName?.takeIf { it.isNotBlank() } ?: read.speakerNumber?.let { "#$it" }
        val out = mutableListOf<Line>()
        val before = current
        if (text == null) {
            send(out)
            reset()
            return Step(out, settle = false)
        }
        val previousShown = shown
        val newLine = before == null ||
            (read.shown != null && previousShown != null && read.shown < previousShown) ||
            (text != before.text && !text.startsWith(before.text))
        if (newLine) {
            send(out)
            current = Line(text, speaker)
            sentText = null
        } else {
            // The same line, typed further (or its speaker read late).
            current = Line(text, speaker ?: before!!.speaker)
        }
        shown = read.shown
        val complete = when {
            read.waiting != null -> read.waiting
            read.shown != null -> read.onScreen == text
            else -> null
        }
        val partway = read.waiting == true && read.shown != null && read.onScreen != null && read.onScreen != text
        if (partway) sendPart(read.onScreen!!, out) else if (complete == true) send(out)
        return Step(out, settle = complete == null && unsent() != null)
    }

    /** Nothing changed since a read asked to settle: the current line, if it hasn't all gone. */
    fun settled(): Line? = unsent()?.also { sentText = it.text }

    /** A new game, or the watch stopped. */
    fun reset() {
        current = null
        shown = null
        sentText = null
    }

    companion object {
        /**
         * [message] without its rows that are one of [answers] (a game may put a mark of its own
         * before each, such as a colon): what's left is the question, if any.
         */
        fun withoutAnswers(message: String, answers: List<String>): String {
            val shown = answers.map { it.trim() }.filter { it.isNotEmpty() }
            return message.split("\n").filterNot { row -> row.trim().let { r -> shown.any { r == it || (r.endsWith(it) && r.length - it.length <= ANSWER_MARK) } } }
                .joinToString("\n")
        }

        /** How many characters a game's mark before an answer row may take. */
        private const val ANSWER_MARK = 2

        /** Whether [message] can be what the game shows: no U+FFFD, no control but newlines. */
        fun shown(message: String): Boolean = message.none { it == '\uFFFD' || (it.isISOControl() && it != '\n') }
    }
}
