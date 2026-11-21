package app.genkan.ppsspp

import android.content.Context
import android.util.Base64
import android.util.Log
import app.genkan.BuildConfig
import app.genkan.memory.SampleInbox
import app.genkan.ocr.OcrReader
import app.genkan.yomikata.CatalogEntry
import app.genkan.yomikata.CleanupStep
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** What the hook finder is doing, for the reader's status line. */
sealed interface HookFinderState {
    data object Idle : HookFinderState
    data class Working(val message: String) : HookFinderState
    data class Found(val message: String) : HookFinderState
    data class Failed(val message: String) : HookFinderState
}

/**
 * Finds a PSP game's text hook during play, by Yomikata's method (docs/hook-finder.md there):
 * the line OCR read is found in memory, read breakpoints on it catch the code that reads the next
 * line, and the entries of those functions are watched for one that is handed each new line in a
 * register. A candidate holding for three lines is checked against OCR for several more, then
 * reported to the Yomikata inbox and used straight away. The player advances the lines; Genkan
 * says when. PPSSPP's debugger gives log events only to its newest connection, so the regular
 * text source steps aside meanwhile.
 */
@Singleton
class HookFinder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ppsspp: PpssppText,
    private val ocr: OcrReader,
    private val inbox: SampleInbox,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<HookFinderState>(HookFinderState.Idle)
    val state: StateFlow<HookFinderState> = _state.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            ppsspp.hold(true)
            try {
                _state.value = find()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = HookFinderState.Idle
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Hook search failed", e)
                _state.value = HookFinderState.Failed("Hook search stopped: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                ppsspp.hold(false)
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.value = HookFinderState.Idle
    }

    /** Back to idle after a result was read. */
    fun dismiss() {
        if (job?.isActive != true) _state.value = HookFinderState.Idle
    }

    private fun say(message: String) {
        _state.value = HookFinderState.Working(message)
        Log.i(TAG, message)
    }

    private suspend fun find(): HookFinderState {
        if (!ppsspp.enabled.first()) return HookFinderState.Failed("Turn on PPSSPP text in settings first: the finder uses its debugger")
        if (!ocr.enabled.value) return HookFinderState.Failed("Turn OCR on first: the finder compares each line with what OCR reads")
        // Let the regular connection close, so this one is PPSSPP's newest.
        delay(500)
        say("Connecting to PPSSPP's debugger…")
        val debugger = Debugger.open()
        try {
            debugger.sweep()
            return search(debugger)
        } finally {
            // Stopped or not, nothing of the search is left running in PPSSPP.
            withContext(NonCancellable) { runCatching { debugger.sweep() } }
            debugger.close()
        }
    }

    private suspend fun search(d: Debugger): HookFinderState {
        val emulator = d.call("version").optString("version")
        val game = d.call("game.status").optJSONObject("game") ?: return HookFinderState.Failed("No game is running in PPSSPP")
        val id = game.getString("id")
        val title = game.optString("title")
        val discVersion = game.optString("version")
        val lines = Channel<String>(Channel.CONFLATED)
        val listener = scope.launch { ocr.lines.collect { lines.send(it) } }
        try {
            // Steps 1 to 3, again from the line then on screen when a round finds nothing.
            say("Go to a line of dialogue (shown in a text box), then wait")
            var line = ocr.latest.value?.takeIf { it.isNotBlank() } ?: lines.receive()
            var found: Round.Found? = null
            for (n in 0 until ROUNDS) {
                when (val r = round(d, id, line, lines)) {
                    is Round.Found -> { found = r; break }
                    is Round.Failed -> return HookFinderState.Failed(r.message)
                    is Round.Again -> {
                        if (n == ROUNDS - 1) return HookFinderState.Failed("No hook found: ${r.message}")
                        Log.i(TAG, "Starting over: ${r.message}")
                        line = r.line
                    }
                }
            }
            val candidate = found!!.candidate
            val cs = found.charset

            // 4. Checked through the candidate alone for several lines.
            addLogBreakpoint(d, candidate.entry, "${MARK}0", listOf(candidate.register))
            d.quiet()
            val raws = mutableListOf<String>()
            val ocrs = mutableListOf<String>()
            for (attempt in 0 until CHECK_LINES * 2) {
                if (ocrs.size >= CHECK_LINES) break
                say("Found a candidate at 0x%08X; checking it: go to the next line (%d of %d)".format(candidate.entry, ocrs.size + 1, CHECK_LINES))
                val hits = collectHits(d, listOf(candidate.entry), emptySet(), cs.second, lines) ?: continue
                val text = hits.second[candidate.entry]?.lastOrNull()?.texts?.get(candidate.register).orEmpty()
                raws += text
                ocrs += hits.first
            }
            runCatching { d.call("cpu.breakpoint.remove", JSONObject().put("address", candidate.entry)) }
            if (ocrs.size < MIN_CHECKED) return HookFinderState.Failed("The candidate at 0x%08X stopped firing; not reported".format(candidate.entry))
            val rules = HookSearch.cleanupFor(raws, ocrs)
            val pairs = raws.zip(ocrs).map { (raw, o) -> HookSearch.Checked(o, HookSearch.clean(raw, rules).orEmpty()) }
            val matched = pairs.count { it.matches() }
            val hook = CatalogEntry.Hook(candidate.entry, candidate.register, cs.first, emptyList(), CleanupStep.parse(rules))
            val report = HookSearch.report(id, title, discVersion, hook, cs.first, rules, pairs, "Genkan", BuildConfig.VERSION_NAME, emulator)
            if (matched * 10 < pairs.size * 8) {
                save(id, report, rejected = true)
                return HookFinderState.Failed("The candidate at 0x%08X matched only %d of %d lines; not reported".format(candidate.entry, matched, pairs.size))
            }
            save(id, report, rejected = false)
            val sent = withContext(Dispatchers.IO) { inbox.sendHook(report.toString()) }
            return HookFinderState.Found(
                "Hook found: 0x%08X %s, %d of %d lines matched; %s".format(candidate.entry, candidate.register, matched, pairs.size, sent),
            )
        } finally {
            listener.cancel()
        }
    }

    private sealed interface Round {
        data class Found(val candidate: HookSearch.Tracker.Candidate, val charset: Pair<String, Charset>) : Round
        data class Failed(val message: String) : Round
        /** Nothing this time; start over from [line], the one now on screen. */
        data class Again(val message: String, val line: String) : Round
    }

    /** A line found in memory, the code reading the next one, and the entry handed each line after. */
    private suspend fun round(d: Debugger, id: String, start: String, lines: Channel<String>): Round {
        var line = start
        // 1. A line on screen, found in memory.
        var copies: List<Long> = emptyList()
        var charset: Pair<String, Charset>? = null
        for (attempt in 0 until 3) {
            say("Looking for \"${line.take(12)}\" in memory…")
            val found = locate(d, line)
            if (found != null) {
                charset = found.first
                copies = found.second
                break
            }
            say("That line isn't in memory as Shift-JIS, UTF-16 or UTF-8; go to the next one")
            line = lines.receive()
        }
        val cs = charset ?: return Round.Failed("Three lines in a row weren't in memory as plain text: this game needs a character table")
        Log.i(TAG, "$id: \"$line\" in ${cs.first} at ${copies.joinToString { "%08x".format(it) }}")

        // 2. Which code reads the next line there.
        d.drain()
        for (a in copies) d.call("memory.breakpoint.add", JSONObject().put("address", a - WINDOW_BEFORE).put("size", WINDOW).put("enabled", false).put("log", true).put("read", true).put("write", false))
        var readers: Map<Long, Int> = emptyMap()
        for (attempt in 0 until 2) {
            say("Found it. Go to the next line")
            line = lines.receive()
            delay(SETTLE_MS)
            readers = d.drain().mapNotNull { CHECK.find(it) }.groupingBy { it.groupValues[1].toLong(16) }.eachCount()
            if (readers.isNotEmpty()) break
            // Nothing at all: PPSSPP may be sending its log to a newer connection.
            d.reconnect()
        }
        for (a in copies) d.call("memory.breakpoint.remove", JSONObject().put("address", a - WINDOW_BEFORE).put("size", WINDOW))
        // PPSSPP hands its log over slowly: the reads it logged keep coming for a while.
        d.quiet()
        if (readers.isEmpty()) return Round.Again("nothing read the next line where the last one was", line)
        val entries = readers.keys.sortedBy { readers[it] }.take(MAX_PCS).mapNotNull { functionStart(d, it) }.distinct().take(MAX_ENTRIES)
        Log.i(TAG, "Readers ${readers.entries.joinToString { "%08x×%d".format(it.key, it.value) }}; entries ${entries.joinToString { "%08x".format(it) }}")

        // 3. The entry handed each new line in a register, three lines running. First, with
        // the line standing still, each entry alone: one that fires anyway reads everything
        // (a renderer, every frame), and its flood would crowd the others out of PPSSPP's log.
        say("Sorting through ${entries.size} places; wait a moment before the next line")
        val quiet = entries.filterIndexed { i, e ->
            addLogBreakpoint(d, e, "${MARK}S$i")
            delay(SCREEN_MS)
            runCatching { d.call("cpu.breakpoint.remove", JSONObject().put("address", e)) }
            val fired = d.quiet().count { "${MARK}S$i " in it }
            Log.i(TAG, "%08x fired %d times while the line stood still".format(e, fired))
            fired <= SCREEN_MAX
        }
        if (quiet.isEmpty()) return Round.Again("every function that read the line runs all the time", line)
        for ((i, e) in entries.withIndex()) if (e in quiet) addLogBreakpoint(d, e, "$MARK$i")
        d.quiet()
        val tracker = HookSearch.Tracker()
        tracker.rejected += entries.filter { it !in quiet }
        var winner: HookSearch.Tracker.Candidate? = null
        for (n in 0 until MAX_LINES) {
            say("Watching ${entries.size - tracker.rejected.size} places; go to the next line (${tracker.best} of 3)")
            val hits = collectHits(d, entries, tracker.rejected, cs.second, lines)
            if (hits == null) {
                say("Lost PPSSPP's log and reconnected; go to the next line")
                continue
            }
            line = hits.first
            winner = tracker.line(line, hits.second)
            Log.i(TAG, "Line \"${line.take(20)}\": " + hits.second.entries.joinToString { (e, list) ->
                val best = list.flatMap { it.texts.entries }.maxByOrNull { HookSearch.fit(HookSearch.rough(it.value), line) }
                "%08x×%d %s".format(e, list.size, best?.let { "${it.key} %.2f".format(HookSearch.fit(HookSearch.rough(it.value), line)) }.orEmpty())
            })
            for (e in hits.second.keys.filter { it in tracker.rejected }) d.call("cpu.breakpoint.remove", JSONObject().put("address", e))
            if (winner != null || tracker.rejected.size == entries.size) break
        }
        for (e in entries) runCatching { d.call("cpu.breakpoint.remove", JSONObject().put("address", e)) }
        val candidate = winner ?: return Round.Again(
            "no hook held for 3 lines (${entries.size} functions read the text; ${tracker.rejected.size} read everything)",
            line,
        )
        Log.i(TAG, "Candidate %08x %s".format(candidate.entry, candidate.register))
        return Round.Found(candidate, cs)
    }


    /** Where [line] is in user RAM, in the first charset it's found in; null when it isn't. */
    private suspend fun locate(d: Debugger, line: String): Pair<Pair<String, Charset>, List<Long>>? {
        val probes = HookSearch.probes(line)
        if (probes.isEmpty()) return null
        val needles = HookSearch.CHARSETS.associateWith { (_, cs) -> probes.map { it.toByteArray(cs) } }
        val found = HookSearch.CHARSETS.associateWith { mutableSetOf<Long>() }
        var address = RAM_START
        var tail = ByteArray(0)
        while (address < RAM_END) {
            val chunk = tail + d.read(address, CHUNK)
            val base = address - tail.size
            for ((charset, list) in needles) for (needle in list) {
                HookSearch.indexesOf(chunk, needle).forEach { found.getValue(charset) += base + it }
            }
            tail = chunk.copyOfRange(chunk.size - OVERLAP, chunk.size)
            address += CHUNK
        }
        val (charset, hits) = found.entries.maxByOrNull { it.value.size }?.takeIf { it.value.isNotEmpty() } ?: return null
        // Probes of one copy lie close together: one place per copy.
        val copies = hits.sorted().fold(mutableListOf<Long>()) { acc, a -> if (acc.isEmpty() || a - acc.last() > WINDOW) acc += a; acc }
        return charset to copies.take(MAX_COPIES)
    }

    /**
     * The breakpoint hits until OCR reads the next line, each with the text its registers point
     * at (read as it fires, before the game moves on), and that line; null when it timed out.
     */
    private suspend fun collectHits(
        d: Debugger,
        entries: List<Long>,
        skip: Set<Long>,
        charset: Charset,
        lines: Channel<String>,
    ): Pair<String, Map<Long, List<HookSearch.Hit>>>? {
        var heard = false
        val hits = HashMap<Long, MutableList<HookSearch.Hit>>()
        val counts = HashMap<Long, Int>()
        var line: String? = null
        var until = Long.MAX_VALUE
        while (System.currentTimeMillis() < until) {
            val message = withTimeoutOrNull(minOf(until - System.currentTimeMillis(), LINE_TIMEOUT_MS).coerceAtLeast(1)) {
                select<String?> {
                    d.logs.onReceive { it }
                    if (line == null) lines.onReceive { line = it; until = System.currentTimeMillis() + SETTLE_MS; null }
                }
            }
            if (message == null) {
                if (line == null) return null
                continue
            }
            heard = true
            val m = HIT.find(message) ?: continue
            val entry = entries.getOrNull(m.groupValues[1].toInt()) ?: continue
            if (entry in skip) continue
            val n = (counts[entry] ?: 0) + 1
            counts[entry] = n
            // A busy entry is counted, not read: it is dropped either way, and taken out now
            // so its flood doesn't push the other hits out of PPSSPP's log.
            if (n > READS_PER_LINE) {
                hits.getOrPut(entry) { mutableListOf() } += HookSearch.Hit(emptyMap())
                if (n == READS_PER_LINE + 1) runCatching { d.call("cpu.breakpoint.remove", JSONObject().put("address", entry)) }
                continue
            }
            val texts = m.groupValues[2].trim().split(' ').mapNotNull { pair ->
                val (register, value) = pair.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                val address = value.toLongOrNull(16) ?: return@mapNotNull null
                if (address !in RAM_START until RAM_END) return@mapNotNull null
                register to PspHooks.decode(d.read(address, READ_SIZE), charset)
            }.toMap()
            hits.getOrPut(entry) { mutableListOf() } += HookSearch.Hit(texts)
        }
        if (!heard && line != null) {
            // Not one log message for a whole line, though the breakpoints are set: PPSSPP is
            // sending its log elsewhere. Connect again and leave this line out.
            d.reconnect()
            return null
        }
        return line?.let { it to hits }
    }

    /** A non-pausing breakpoint at [address] that logs [mark] and the registers' values. */
    private suspend fun addLogBreakpoint(d: Debugger, address: Long, mark: String, registers: List<String> = REGISTERS) {
        // PPSSPP checks an address its JIT already compiled only when a breakpoint is newly
        // added there; an add right after a remove can lose that race, so wait for the answer.
        runCatching { d.call("cpu.breakpoint.remove", JSONObject().put("address", address)) }
        d.call(
            "cpu.breakpoint.add",
            JSONObject().put("address", address).put("enabled", false).put("log", true)
                .put("logFormat", "$mark " + registers.joinToString(" ") { "$it={$it:x}" }),
        )
    }

    /**
     * The entry of the function [pc] is in: PPSSPP's own name for it (`z_un_0885af64`) when it
     * has one, else the nearest stack-frame setup before it.
     */
    private suspend fun functionStart(d: Debugger, pc: Long): Long? {
        val named = d.call("memory.disasm", JSONObject().put("address", pc).put("count", 1))
            .optJSONArray("lines")?.optJSONObject(0)?.optString("function").orEmpty()
        UNNAMED.matchEntire(named)?.let { return it.groupValues[1].toLong(16) }
        val from = (pc - BACK_SEARCH).coerceAtLeast(RAM_START)
        val code = d.read(from, (pc - from).toInt() + 4)
        for (offset in (code.size - 4 downTo 0 step 4)) {
            val word = (code[offset].toInt() and 0xFF) or ((code[offset + 1].toInt() and 0xFF) shl 8) or
                ((code[offset + 2].toInt() and 0xFF) shl 16) or ((code[offset + 3].toInt() and 0xFF) shl 24)
            if (HookSearch.isFrameSetup(word)) return from + offset
        }
        return null
    }

    /** Keeps the report on the device: a found hook is used from then on, until the catalog has one. */
    private fun save(id: String, report: JSONObject, rejected: Boolean) {
        val dir = File(context.filesDir, if (rejected) "found-hooks/rejected" else "found-hooks").apply { mkdirs() }
        File(dir, "$id.json").writeText(report.toString(2))
    }

    /**
     * The debugger: answers by ticket, log messages in [logs]. PPSSPP sends log events to its
     * newest connection only, and to none once that one closes, so a connection that went quiet
     * is replaced ([reconnect]); breakpoints belong to PPSSPP and stay.
     */
    private class Debugger private constructor(private val scope: CoroutineScope) {
        val logs = Channel<String>(Channel.UNLIMITED)
        private val waiting = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
        private val tickets = AtomicInteger()
        @Volatile private var ws: DebuggerSocket? = null
        private var reader: Job? = null

        private suspend fun connect() {
            val socket = withContext(Dispatchers.IO) { DebuggerSocket.connect("127.0.0.1", PpssppText.PORT, "/debugger", CONNECT_TIMEOUT_MS) }
            ws = socket
            reader = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val text = socket.receive() ?: break
                        val event = runCatching { JSONObject(text) }.getOrNull() ?: continue
                        when {
                            event.optString("event") == "log" -> logs.trySend(event.optString("message"))
                            event.has("ticket") -> waiting.remove(event.getString("ticket"))?.complete(event)
                        }
                    }
                } catch (e: IOException) {
                    Log.i(TAG, "Debugger connection closed: $e")
                }
                if (ws === socket) waiting.values.forEach { it.completeExceptionally(IOException("PPSSPP's debugger closed")) }
            }
        }

        /** A new connection in place of this one, so PPSSPP's log events come here again. */
        suspend fun reconnect() {
            val old = ws
            reader?.cancel()
            old?.close()
            connect()
            Log.i(TAG, "Reconnected to PPSSPP's debugger")
        }

        suspend fun call(event: String, params: JSONObject = JSONObject()): JSONObject {
            val ticket = "hf${tickets.incrementAndGet()}"
            val answer = CompletableDeferred<JSONObject>()
            waiting[ticket] = answer
            val socket = ws ?: throw IOException("not connected")
            withContext(Dispatchers.IO) { socket.send(params.put("event", event).put("ticket", ticket).toString()) }
            val reply = withTimeout(CALL_TIMEOUT_MS) { answer.await() }
            if (reply.optString("event") == "error") throw IOException("PPSSPP: ${reply.optString("message")}")
            return reply
        }

        /** [size] bytes of PSP memory at [address]. */
        suspend fun read(address: Long, size: Int): ByteArray =
            Base64.decode(call("memory.read", JSONObject().put("address", address).put("size", size)).optString("base64"), Base64.DEFAULT)

        /** Removes the finder's breakpoints, this run's or a stopped one's: read ones on user RAM, and those logging [MARK]. */
        suspend fun sweep() {
            call("cpu.breakpoint.list").optJSONArray("breakpoints")?.let { list ->
                for (i in 0 until list.length()) {
                    val b = list.getJSONObject(i)
                    if (b.optString("logFormat").startsWith(MARK)) call("cpu.breakpoint.remove", JSONObject().put("address", b.getLong("address")))
                }
            }
            call("memory.breakpoint.list").optJSONArray("breakpoints")?.let { list ->
                for (i in 0 until list.length()) {
                    val b = list.getJSONObject(i)
                    if (b.optBoolean("read") && !b.optBoolean("write") && b.optInt("size") == WINDOW) {
                        call("memory.breakpoint.remove", JSONObject().put("address", b.getLong("address")).put("size", WINDOW))
                    }
                }
            }
        }

        /** Waits for the log to go quiet for a moment (at most [QUIET_MAX_MS]); returns what came meanwhile. */
        suspend fun quiet(): List<String> {
            val taken = mutableListOf<String>()
            val until = System.currentTimeMillis() + QUIET_MAX_MS
            while (System.currentTimeMillis() < until) {
                val message = withTimeoutOrNull(QUIET_MS) { logs.receive() } ?: break
                taken += message
            }
            return taken
        }

        /** The log messages so far, taken. */
        fun drain(): List<String> = generateSequence { logs.tryReceive().getOrNull() }.toList()

        fun close() {
            reader?.cancel()
            ws?.close()
        }

        companion object {
            suspend fun open(): Debugger = Debugger(CoroutineScope(SupervisorJob() + Dispatchers.IO)).apply { connect() }
        }
    }

    private companion object {
        const val TAG = "HookFinder"
        const val MARK = "GENKANHF"
        /** PSP user memory. */
        const val RAM_START = 0x08800000L
        const val RAM_END = 0x0A000000L
        const val CHUNK = 0x100000
        const val OVERLAP = 64
        /** Read breakpoints cover a little before a copy and the lines after it (a script's next line). */
        const val WINDOW_BEFORE = 0x100L
        const val WINDOW = 0x800
        const val MAX_COPIES = 6
        const val MAX_PCS = 64
        const val MAX_ENTRIES = 12
        const val MAX_LINES = 8
        const val ROUNDS = 3
        const val SCREEN_MS = 1_200L
        const val SCREEN_MAX = 2
        const val QUIET_MS = 1_000L
        const val QUIET_MAX_MS = 30_000L
        const val CHECK_LINES = 8
        const val MIN_CHECKED = 5
        const val READS_PER_LINE = 4
        const val READ_SIZE = 512
        const val BACK_SEARCH = 0x2000L
        const val SETTLE_MS = 800L
        const val LINE_TIMEOUT_MS = 120_000L
        const val CALL_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 60_000
        val REGISTERS = listOf("a0", "a1", "a2", "a3", "t0", "t1", "t2", "t3")
        val CHECK = Regex("PC=([0-9a-fA-F]{8})")
        val HIT = Regex("$MARK(\\d+)((?: [a-z0-9]+=[0-9a-fA-F]+)+)")
        val UNNAMED = Regex("z_un_([0-9a-fA-F]{8})")
    }
}
