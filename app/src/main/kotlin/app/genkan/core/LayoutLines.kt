package app.genkan.core

import app.genkan.yomikata.LayoutRead

/**
 * Which of a layout's reads make lines for the backlog. A line goes when it's complete: the game
 * waits for a press (`waiting`), or (without that flag) it has typed out in full (`shown`), or
 * (with neither) it stops changing ([Step.settle]). A line that never counts as complete still
 * goes when it's replaced by one that doesn't continue it, or the message clears: a game that
 * advances by itself, or a line skipped before it finished typing. Each line goes once, so the
 * same sentence twice in a row is two lines.
 */
class LayoutLines {
    data class Line(val text: String, val speaker: String?)

    /** Lines to put in the backlog now; [settle]: the current line goes if nothing changes for a while. */
    data class Step(val lines: List<Line>, val settle: Boolean)

    private var current: Line? = null
    private var shown: Long? = null
    private var sent = false

    fun read(read: LayoutRead): Step {
        val text = read.message?.replace("\n", "")?.takeIf { it.isNotBlank() }
        val speaker = read.speakerName?.takeIf { it.isNotBlank() } ?: read.speakerNumber?.let { "#$it" }
        val out = mutableListOf<Line>()
        val before = current
        if (text == null) {
            if (before != null && !sent) out += before
            reset()
            return Step(out, settle = false)
        }
        val previousShown = shown
        val newLine = before == null ||
            (read.shown != null && previousShown != null && read.shown < previousShown) ||
            (text != before.text && !text.startsWith(before.text))
        if (newLine) {
            if (before != null && !sent) out += before
            current = Line(text, speaker)
            sent = false
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
        if (complete == true && !sent) {
            out += current!!
            sent = true
        }
        return Step(out, settle = complete == null && !sent)
    }

    /** Nothing changed since a read asked to settle: the current line, if it hasn't gone. */
    fun settled(): Line? = current?.takeIf { !sent }?.also { sent = true }

    /** A new game, or the watch stopped. */
    fun reset() {
        current = null
        shown = null
        sent = false
    }
}
