package app.genkan.core

import app.genkan.reader.HookMessage
import org.json.JSONObject

/**
 * What a Yomikata script's events (yomikata_reader.h) mean for the reader. Dialogue goes to the
 * backlog whole, with its speaker and the script's id for it (the same id again is a reread, not
 * counted twice); a choice becomes the choice on screen, its cursor following the game's, and the
 * answer taken goes to the backlog when it closes ([answerLine]). Interface text (menus,
 * item names) never reaches the backlog, and neither does a channel this doesn't know. Which of
 * the script's windows are open says whether OCR, kept on for screens the script doesn't read,
 * may put its lines in the backlog.
 */
class ScriptReader {

    /**
     * What one batch of events gives: lines for the backlog, the choice now, whether the script
     * died, and whether where its text is drawn ([layouts]) changed.
     */
    data class Step(val lines: List<HookMessage>, val choice: BuiltInText.Choice?, val dead: Boolean, val layoutsChanged: Boolean = false)

    /**
     * Where a window's text is drawn: the text (dialogue, a choice's answers or interface text),
     * the layout's own [width] × [height] space, and a cell per code point of the text (null where
     * nothing is drawn); [screen] 1 is a DS's lower one.
     */
    data class WindowLayout(
        val window: String,
        val text: String,
        val channel: String,
        val width: Float,
        val height: Float,
        val glyphs: List<FloatArray?>,
        val screen: Int = 0,
    )

    /** The last text in each window, and its seq, to give a layout its text. */
    private val windowTexts = HashMap<String, Triple<Long, String, String>>()
    private val windowLayouts = LinkedHashMap<String, WindowLayout>()
    private var layoutsChanged = false

    /** Where the script's texts are drawn now, a window each, in the order they came up. */
    val layouts: List<WindowLayout> get() = windowLayouts.values.toList()

    private var choiceWindow: String? = null
    private var answers: List<String> = emptyList()
    private var question: String? = null
    private var cursor: Int? = null
    private val openWindows = HashSet<String>()
    private var lastTextMs = Long.MIN_VALUE / 2
    private var dead = false

    /** The id of the last text the script gave, for a snapshot's meta. */
    var lastTextId: String? = null
        private set

    /** The choice up now, or null. */
    val choice: BuiltInText.Choice?
        get() = if (answers.isEmpty()) null else BuiltInText.Choice(answers, cursor)

    /** [events] (kind and JSON, as GenkanTap.scriptFrame gives them) that arrived at [nowMs]. */
    fun read(events: List<Pair<Int, String>>, nowMs: Long): Step {
        val lines = mutableListOf<HookMessage>()
        layoutsChanged = false
        for ((kind, json) in events) {
            val event = runCatching { JSONObject(json) }.getOrNull() ?: continue
            when (kind) {
                ScriptEvents.TEXT -> text(event, nowMs, lines)
                ScriptEvents.LAYOUT -> layout(event, lines)
                ScriptEvents.CURSOR -> cursor(event, lines)
                ScriptEvents.LOG -> log(event.optString("level"), event.optString("message"))
            }
        }
        return Step(lines, choice, dead, layoutsChanged)
    }

    private fun text(event: JSONObject, nowMs: Long, lines: MutableList<HookMessage>) {
        val text = event.optString("text")
        val window = event.optString("window").takeIf { it.isNotEmpty() }
        lastTextId = event.optString("id").takeIf { it.isNotEmpty() } ?: lastTextId
        // The window's new text takes the place of its last one, and of where that was drawn.
        if (window != null) {
            windowTexts[window] = Triple(event.optLong("seq"), text, event.optString("channel", DIALOGUE))
            if (windowLayouts.remove(window) != null) layoutsChanged = true
        }
        // A new text in the choice's window takes the choice's place.
        if (window != null && window == choiceWindow && event.optString("channel") != CHOICE) closeChoice(lines)
        when (event.optString("channel", DIALOGUE)) {
            DIALOGUE -> {
                if (text.isBlank()) return
                window?.let(openWindows::add)
                lastTextMs = nowMs
                question = text.replace("\n", "")
                lines += HookMessage(
                    text,
                    whole = true,
                    speaker = event.optString("speaker").takeIf { it.isNotBlank() },
                    sourceId = event.optString("id").takeIf { it.isNotEmpty() }?.let { "$SOURCE$it" },
                    ruby = app.genkan.reader.Ruby.fromCodePoints(text, event.optJSONArray("ruby")),
                )
            }
            CHOICE -> {
                closeChoice(lines)
                window?.let(openWindows::add)
                lastTextMs = nowMs
                answers = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                choiceWindow = window
                cursor = null
            }
            // Interface text is for looking up on the picture only (M5c); other channels are dropped.
            else -> Unit
        }
    }

    /**
     * The runtime says how many frame callbacks the script has when it loads, and switches one off
     * that fails five times running: the script is dead once all of them are.
     */
    private fun log(level: String, message: String) {
        LOADED.find(message)?.let { callbacks = it.groupValues[1].toInt() }
        if (level == "error" && DISABLED.containsMatchIn(message) && ++disabled >= callbacks) dead = true
    }

    private var callbacks = 1
    private var disabled = 0

    private fun layout(event: JSONObject, lines: MutableList<HookMessage>) {
        val window = event.optString("window").takeIf { it.isNotEmpty() } ?: return
        if (event.optBoolean("closed")) {
            openWindows.remove(window)
            if (windowLayouts.remove(window) != null) layoutsChanged = true
            if (window == choiceWindow) closeChoice(lines)
        } else {
            openWindows.add(window)
            // The layout is for the window's text of the same seq; one for an older text is dropped.
            val (seq, text, channel) = windowTexts[window] ?: return
            if (event.optLong("seq") != seq) return
            val space = event.optJSONArray("space") ?: return
            val glyphs = event.optJSONArray("glyphs") ?: return
            windowLayouts[window] = WindowLayout(
                window, text, channel, space.optDouble(0).toFloat(), space.optDouble(1).toFloat(),
                (0 until glyphs.length()).map { i -> glyphs.optJSONArray(i)?.let { c -> FloatArray(4) { c.optDouble(it).toFloat() } } },
                event.optInt("screen", 0),
            )
            layoutsChanged = true
        }
    }

    private fun cursor(event: JSONObject, lines: MutableList<HookMessage>) {
        if (event.optBoolean("closed")) {
            closeChoice(lines)
        } else if (answers.isNotEmpty()) {
            cursor = event.optInt("index", -1)
        }
    }

    /** The choice closes: the answer under the cursor goes to the backlog, when there is one. */
    private fun closeChoice(lines: MutableList<HookMessage>) {
        if (answers.isEmpty()) return
        val taken = cursor?.takeIf { it in answers.indices }?.let { answers[it] }
        if (taken != null) lines += HookMessage(answerLine(taken), whole = true)
        answers = emptyList()
        cursor = null
        choiceWindow?.let(openWindows::remove)
        choiceWindow = null
    }

    /**
     * Whether a script window is up, or the script gave text in the last [quietMs]: OCR, kept on
     * for screens the script doesn't read, then keeps its lines out of the backlog.
     */
    fun windowOpen(nowMs: Long, quietMs: Long = QUIET_MS): Boolean = openWindows.isNotEmpty() || nowMs - lastTextMs < quietMs

    /** After a savestate was loaded: the runtime closed every layout, and nothing is up. */
    fun stateLoaded() {
        windowTexts.clear()
        windowLayouts.clear()
        answers = emptyList()
        cursor = null
        choiceWindow = null
        openWindows.clear()
    }

    companion object {
        /** How an answer reads in the backlog: marked as the player's, after the question. */
        fun answerLine(answer: String): String = "→ $answer"

        const val DIALOGUE = "dialogue"
        const val CHOICE = "choice"
        const val INTERFACE = "interface"
        /** A reading source id for the script's text id: never one of a line's own hash. */
        const val SOURCE = "script:"
        /** After the script's last text, how long OCR stays out of the backlog without a window open. */
        const val QUIET_MS = 4_000L
        /** The runtime's error when it switches a frame callback off after it failed five times running. */
        private val DISABLED = Regex("callback disabled")
        /** The runtime's line when the script loaded: how many frame callbacks it has. */
        private val LOADED = Regex("""(\d+) frame callback""")
    }
}
