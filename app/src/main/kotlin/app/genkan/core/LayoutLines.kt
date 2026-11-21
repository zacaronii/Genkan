package app.genkan.core

import app.genkan.yomikata.LayoutRead

/**
 * Which of a layout's reads make lines for the backlog. A line goes when it's complete: the game
 * waits for a press (`waiting`), or (without that flag) it has typed out in full (`shown`), or
 * (with neither) it stops changing ([Step.settle]). A line that never counts as complete still
 * goes when it's replaced by one that doesn't continue it, or the message clears: a game that
 * advances by itself, or a line skipped before it finished typing. Each line goes once, so the
 * same sentence twice in a row is two lines; a line that grows after it went (a box that adds a
 * sentence and waits again) goes again, taking back what went before.
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

    private fun send(out: MutableList<Line>) {
        unsent()?.let {
            out += it
            sentText = it.text
        }
    }

    fun read(read: LayoutRead): Step {
        val text = read.message?.replace("\n", "")?.takeIf { it.isNotBlank() }
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
        if (complete == true) send(out)
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
}
