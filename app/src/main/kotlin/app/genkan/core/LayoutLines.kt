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
 * that much: that much goes, and the rest follows as the line grows. A window that scrolls by whole
 * rows, like a terminal (the old rows less the first one or more, then new ones), keeps
 * the rows that stayed out of its lines: each line is the rows after them, so no row goes twice.
 */
class LayoutLines {
    /** [takesBack]: the text of this line that went before, which this one replaces. */
    data class Line(val text: String, val speaker: String?, val takesBack: String? = null)

    /** Lines to put in the backlog now; [settle]: the current line goes if nothing changes for a while. */
    data class Step(val lines: List<Line>, val settle: Boolean)

    private var current: Line? = null
    private var shown: Long? = null
    /** The last choice's page (rows joined) and answers, until the message moves on from it. */
    private var choice: Pair<String, List<String>>? = null

    /** What of the current line has gone, or null. */
    private var sentText: String? = null

    /** The last read's rows, for seeing a scroll. */
    private var lastRows: List<String>? = null

    /** The rows at the top of the window that went before it scrolled: not part of the current line. */
    private var kept: List<String> = emptyList()

    /** The layout that read the current line, when the watch says. */
    private var currentLayout: Int? = null

    /**
     * The last line that went whole, the layout that read it, and whether only empty reads have
     * come since: a version's next layout taking over can read the same text again (a choice's
     * question, read by the typing layout and then by the choice layout), which isn't a new line.
     */
    private var lastLine: String? = null
    private var lastLayout: Int? = null
    private var onlyEmptySince = false

    /** The current line has gone whole. */
    private fun wentWhole() {
        lastLine = current?.text
        lastLayout = currentLayout
        onlyEmptySince = true
    }

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
            wentWhole()
        }
    }

    fun read(read: LayoutRead): Step {
        // A message with a character no script has (U+FFFD, a control other than a row break)
        // is memory that isn't the message: nothing is shown then. While a choice is up its
        // answers are shown as a list of their own, not as a line: only the question goes.
        // A game that leaves the page up for a frame or two after an answer is taken (choosing
        // false, the answers still in the message) is still showing the choice.
        val page = read.message?.replace("\n", "")
        if (read.asking) {
            choice = page?.let { it to read.choices.orEmpty() }
        } else if (choice != null && !page.isNullOrEmpty() && !choice!!.first.startsWith(page)) {
            choice = null
        }
        val answers = if (read.asking) read.choices.orEmpty() else choice?.takeIf { page != null && it.first.startsWith(page) }?.second
        val message = if (answers != null) read.message?.let { withoutAnswers(it, answers) } else read.message
        val rows = message?.takeIf { shown(it) }?.split("\n")
        val scrolled = rows != null && lastRows?.let { scrolledRows(it, rows) }?.let { kept = it } != null
        lastRows = rows
        // A window that no longer starts with the rows kept is a new passage.
        if (!scrolled && kept.isNotEmpty() && (rows == null || rows.take(kept.size) != kept)) kept = emptyList()
        val text = rows?.drop(kept.size)?.joinToString("")?.takeIf { it.isNotBlank() }
        // What has typed out of the current line: the window's, less the kept rows.
        val keptText = kept.joinToString("")
        val onScreen = read.onScreen?.let { if (it.startsWith(keptText)) it.substring(keptText.length) else "" }
        val speaker = read.speakerName?.takeIf { it.isNotBlank() } ?: read.speakerNumber?.let { "#$it" }
        val out = mutableListOf<Line>()
        // The page typed out before the game said it's a choice: what of it has typed is this
        // page's answers (and question), so only the question stays the current line.
        current?.takeIf { read.asking && page != null && page.startsWith(it.text) }?.let { typed ->
            if (text == null) {
                clearLine()
                return Step(out, settle = false)
            }
            current = typed.copy(text = text)
        }
        val before = current
        if (text == null) {
            send(out)
            clearLine()
            return Step(out, settle = false)
        }
        val previousShown = shown
        val newLine = before == null || scrolled ||
            (read.shown != null && previousShown != null && read.shown < previousShown) ||
            (text != before.text && !text.startsWith(before.text))
        if (newLine) {
            send(out)
            current = Line(text, speaker)
            // Another layout of the version reading the line that just went, with only empty
            // reads between: the same line handed over, not said again.
            val handedOver = onlyEmptySince && text == lastLine && read.layout != null && lastLayout != null && read.layout != lastLayout
            sentText = if (handedOver) text else null
            onlyEmptySince = handedOver
            currentLayout = read.layout
        } else {
            // The same line, typed further (or its speaker read late).
            current = Line(text, speaker ?: before!!.speaker)
        }
        shown = read.shown
        val complete = when {
            read.waiting != null -> read.waiting
            read.shown != null -> onScreen == text
            else -> null
        }
        val partway = read.waiting == true && read.shown != null && onScreen != null && onScreen != text
        if (partway) sendPart(onScreen!!, out) else if (complete == true) send(out)
        return Step(out, settle = complete == null && unsent() != null)
    }

    /** The current line, if it is [text] and hasn't all gone: sent now, as [settled] would. */
    fun sendIfCurrent(text: String): Line? = current?.takeIf { it.text == text }?.let { settled() }

    /** Nothing changed since a read asked to settle: the current line, if it hasn't all gone. */
    fun settled(): Line? = unsent()?.also {
        sentText = it.text
        wentWhole()
    }

    /** A new game, or the watch stopped. */
    /**
     * The rows of [now] that stayed from [before] when the window scrolled by whole rows: [before]
     * less its first row or rows, at the top of [now] with a new row after them. Null: no scroll
     * (the same window, a new page, or rows that are all blank).
     */
    private fun scrolledRows(before: List<String>, now: List<String>): List<String>? {
        for (gone in 1 until before.size) {
            val stayed = before.subList(gone, before.size)
            if (now.size > stayed.size && now.subList(0, stayed.size) == stayed && stayed.any { it.isNotBlank() }) return stayed
        }
        return null
    }

    fun reset() {
        clearLine()
        choice = null
        lastLine = null
        lastLayout = null
        onlyEmptySince = false
        lastRows = null
        kept = emptyList()
    }

    /** No current line (the message cleared), the last choice kept. */
    private fun clearLine() {
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
