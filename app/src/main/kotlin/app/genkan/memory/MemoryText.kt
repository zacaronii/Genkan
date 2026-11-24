package app.genkan.memory

import android.util.Log
import app.genkan.ocr.OcrReader
import app.genkan.ppsspp.PspHooks
import app.genkan.reader.HookMessage
import app.genkan.reader.ReaderSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A string found in memory and what it says now. */
data class FoundText(val hit: TextHit, val start: Long, val text: String)

/**
 * What a memory text source is doing, for the reader's status line: [Locked] follows the text in
 * memory with OCR off; [Script] keeps OCR on to tell which page shows, and gives that page's
 * exact text from the game's script.
 */
enum class MemoryTextStatus { Off, NeedsOcr, Searching, NotFound, Locked, Script }

/**
 * Game text found in memory that can only be read, with no text routine to watch. A line OCR
 * read once is looked up in the game's memory. When it sits in a string of its own, that string
 * (or the pointer to it) is read again as the game moves on, with OCR switched off. When it is a
 * page of a whole script the game keeps loaded, with no cursor to follow, OCR stays on to say
 * which page shows, and each line it reads is swapped for that page's exact text.
 */
class MemoryText(
    private val session: ReaderSession,
    private val ocr: OcrReader,
    private val tag: String,
    /** What to do with a line that isn't in memory (the built-in player saves it for Yomikata). */
    private val onMissing: (suspend (MemorySource, String) -> Unit)? = null,
) {
    /** Lines handed in as if OCR had read them (debug builds). */
    val offered = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val _status = MutableStateFlow(MemoryTextStatus.Off)
    val status: StateFlow<MemoryTextStatus> = _status.asStateFlow()

    fun off() {
        _status.value = MemoryTextStatus.Off
    }

    /**
     * Looks up each line OCR reads in the memory [open] gives, and follows the first one found,
     * until cancelled. [open] runs on the IO dispatcher and may return null when the game isn't
     * reachable yet.
     */
    suspend fun follow(open: () -> MemorySource?) {
        _status.value = MemoryTextStatus.NeedsOcr
        try {
            coroutineScope {
                var watching: Job? = null
                var lastTry = 0L
                merge(ocr.lines, offered).collect { line ->
                    if (watching?.isActive == true || script != null) return@collect
                    // A search reads the whole game's memory: not for every line OCR reads.
                    if (System.currentTimeMillis() - lastTry < RETRY_MS) return@collect
                    lastTry = System.currentTimeMillis()
                    _status.value = MemoryTextStatus.Searching
                    when (val found = runCatching { lockOn(open, line) }.onFailure { Log.w(tag, "Search failed: $it") }.getOrNull()) {
                        null -> {
                            Log.i(tag, "Not in memory: ${line.take(24)}")
                            _status.value = MemoryTextStatus.NotFound
                            if (onMissing != null) {
                                withContext(Dispatchers.IO) { open()?.use { onMissing.invoke(it, line) } }
                            }
                        }
                        is Found.Script -> {
                            Log.i(tag, "Found a script page at 0x%08x (%s)".format(found.page.address, found.page.charset.name()))
                            script = found.page
                            scriptMisses = 0
                            ocr.corrector = { ocrLine -> correct(open, ocrLine) }
                            _status.value = MemoryTextStatus.Script
                        }
                        is Found.Line -> {
                            val lock = found.lock
                            Log.i(tag, "Locked on 0x%08x (%s), %d pointers to it".format(lock.found.start, lock.found.hit.charset.name(), lock.pointers.size))
                            // Memory now gives the lines; OCR would only repeat them.
                            ocr.setEnabled(false)
                            _status.value = MemoryTextStatus.Locked
                            watching = launch {
                                watch(open, lock)
                                _status.value = MemoryTextStatus.NeedsOcr
                            }
                        }
                    }
                }
            }
        } finally {
            dropScript()
        }
    }

    @Volatile private var script: ScriptPage? = null
    @Volatile private var scriptMisses = 0

    private fun dropScript() {
        script = null
        ocr.corrector = null
    }

    /**
     * The page of the script near the last one that OCR's [line] is, as the game has it; null
     * when none is close enough. A few misses in a row mean another script was loaded: the next
     * line is searched for again.
     */
    private suspend fun correct(open: () -> MemorySource?, line: String): String? = withContext(Dispatchers.IO) {
        val page = script ?: return@withContext null
        val best = open()?.use { memory -> pageNear(memory, page.address, page.charset, line, SCRIPT_WINDOW) }
        if (best == null) {
            if (++scriptMisses >= SCRIPT_MISSES) {
                Log.i(tag, "Lost the script; searching again")
                dropScript()
                _status.value = MemoryTextStatus.NeedsOcr
            }
            return@withContext null
        }
        scriptMisses = 0
        script = best
        best.text
    }

    /** The page of a script within [window] bytes of [address] that reads most like [line]. */
    private fun pageNear(memory: MemorySource, address: Long, charset: java.nio.charset.Charset, line: String, window: Long): ScriptPage? {
        val region = memory.regions.firstOrNull { address in it } ?: return null
        // Even bounds keep UTF-16 text aligned.
        val from = maxOf(region.first, (address - window) and 1L.inv())
        val to = minOf(region.last + 1, address + window)
        val bytes = readSpan(memory, from, (to - from).toInt()) ?: return null
        val (best, score) = MemoryScan.bestRun(bytes, charset, line) ?: return null
        if (score < PAGE_SIMILARITY) return null
        return ScriptPage(from + best.offset, charset, best.text)
    }

    /** [size] bytes at [address], in as many reads as the source needs. */
    private fun readSpan(memory: MemorySource, address: Long, size: Int): ByteArray? {
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            val n = minOf(memory.maxRead, size - done)
            val chunk = memory.read(address + done, n) ?: return null
            chunk.copyInto(out, done)
            done += n
        }
        return out
    }

    /**
     * The string OCR's [line] is in, and the pointers the game keeps to it; or, when the line is
     * a page of a longer script, that page.
     */
    private suspend fun lockOn(open: () -> MemorySource?, line: String): Found? = withContext(Dispatchers.IO) {
        open()?.use { memory ->
            val results = search(memory, line)
            val candidate = results.firstOrNull { MemoryScan.matches(it.text, line) }
                ?: return@use results.firstNotNullOfOrNull { pageNear(memory, it.hit.address, it.hit.charset, line, PAGE_CONTEXT) }
                    ?.let { Found.Script(it) }
            // More script right after the line: the game steps through a table of lines (Layton,
            // Kanken), often with no pointer that moves, so OCR keeps reading and memory corrects it.
            val after = memory.read(candidate.start, TABLE_PEEK)
            if (after != null && MemoryScan.scriptLines(after, candidate.hit.charset) >= 2) {
                pageNear(memory, candidate.start, candidate.hit.charset, line, PAGE_CONTEXT)?.let { return@use Found.Script(it) }
            }
            // Pointers to anywhere between the guessed start and the hit: the game's own pointer
            // also says where the string really starts.
            val pointers = mutableListOf<Pair<Long, Long>>()
            scan(memory, overlap = 0) { chunk, base -> pointers += MemoryScan.pointersInto(chunk, base, candidate.start..candidate.hit.address, memory.bigEndian, memory::target) }
            val start = pointers.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.key ?: candidate.start
            val found = if (start == candidate.start) candidate else {
                val bytes = memory.read(start, LINE_BYTES) ?: return@use null
                candidate.copy(start = start, text = PspHooks.decode(bytes, candidate.hit.charset))
            }
            Found.Line(Lock(found, pointers.filter { it.second == start }.map { it.first }.take(MAX_POINTERS)))
        }
    }

    /**
     * Reads the locked string again and again. A game that writes each line into the same
     * buffer changes the string; one that points at lines in a table moves a pointer to it
     * instead, so a moved pointer moves the watch. A line is passed on once it reads the same
     * twice (the game may still be writing it).
     */
    private suspend fun watch(open: () -> MemorySource?, lock: Lock) = withContext(Dispatchers.IO) {
        open()?.use { memory ->
            val charset = lock.found.hit.charset
            var current = lock.found.start
            val pointers = lock.pointers.associateWith { current }.toMutableMap()
            var lastSent = MemoryScan.clean(lock.found.text)
            var previous = lastSent
            var misses = 0
            while (isActive) {
                delay(POLL_MS)
                for ((slot, last) in pointers) {
                    val value = memory.read(slot, 4)?.let { MemoryScan.u32(it, bigEndian = memory.bigEndian) }?.let(memory::target) ?: continue
                    if (value != last) {
                        pointers[slot] = value
                        current = value
                    }
                }
                val bytes = memory.read(current, LINE_BYTES)
                if (bytes == null) {
                    // Three silent reads: the game closed.
                    if (++misses >= 3) return@use
                    continue
                }
                misses = 0
                val text = MemoryScan.clean(PspHooks.decode(bytes, charset))
                if (text == previous && text != lastSent && text.isNotEmpty()) {
                    if (!ocr.quiet) session.submit(HookMessage(text))
                    lastSent = text
                }
                previous = text
            }
        }
    }

    /**
     * Strings in the game's memory that look like [line]: every place a few of its characters
     * occur, widened to the whole string there, best match first.
     */
    fun search(memory: MemorySource, line: String): List<FoundText> {
        val needles = MemoryScan.charsets(memory.bigEndian).flatMap { charset ->
            MemoryScan.probes(line).map { probe -> charset to probe.toByteArray(charset) }
        }
        val hits = linkedSetOf<TextHit>()
        // Even, so offsets in the window keep the parity of the addresses UTF-16 hits align to.
        val overlap = (needles.maxOf { it.second.size } - 1 + 1) and 1.inv()
        scan(memory, overlap) { window, base ->
            for ((charset, needle) in needles) {
                for (offset in MemoryScan.find(window, needle, aligned = MemoryScan.unit(charset) == 2)) {
                    hits += TextHit(base + offset, charset)
                }
            }
        }
        return hits.take(MAX_HITS)
            .mapNotNull { hit -> widen(memory, hit) }
            .distinctBy { it.start }
            .sortedByDescending { MemoryScan.similarity(MemoryScan.clean(it.text), line) }
    }

    /** Every pointer in the game's memory into [targets], as slot to address (debug builds). */
    fun pointers(memory: MemorySource, targets: LongRange): List<Pair<Long, Long>> {
        val found = mutableListOf<Pair<Long, Long>>()
        scan(memory, overlap = 0) { chunk, base -> found += MemoryScan.pointersInto(chunk, base, targets, memory.bigEndian, memory::target) }
        return found
    }

    /** Hands [visit] every readable stretch of the game's memory, each with [overlap] bytes of the one before. */
    private fun scan(memory: MemorySource, overlap: Int, visit: (ByteArray, Long) -> Unit) {
        for (region in memory.regions) {
            var address = region.first
            var carry = ByteArray(0)
            while (address <= region.last) {
                val size = minOf(memory.maxRead.toLong(), region.last - address + 1).toInt()
                val chunk = memory.read(address, size)
                if (chunk == null || chunk.all { it == 0.toByte() }) {
                    carry = ByteArray(0)
                } else {
                    visit(carry + chunk, address - carry.size)
                    carry = if (overlap in 1..chunk.size) chunk.copyOfRange(chunk.size - overlap, chunk.size) else ByteArray(0)
                }
                address += size
            }
        }
    }

    /** The whole string around [hit]. */
    private fun widen(memory: MemorySource, hit: TextHit): FoundText? {
        // Near a region's edge, read only what is there.
        val region = memory.regions.firstOrNull { hit.address in it }
        val from = maxOf(hit.address - CONTEXT, region?.first ?: (hit.address - CONTEXT))
        val to = minOf(hit.address + CONTEXT, region?.let { it.last + 1 } ?: (hit.address + CONTEXT))
        val bytes = memory.read(from, (to - from).toInt()) ?: return null
        val start = MemoryScan.stringStart(bytes, (hit.address - from).toInt(), hit.charset)
        val text = PspHooks.decode(bytes.copyOfRange(start, bytes.size), hit.charset)
        return FoundText(hit, from + start, text)
    }

    private data class Lock(val found: FoundText, val pointers: List<Long>)

    /** A page of a script in memory, where it starts and what it shows. */
    private data class ScriptPage(val address: Long, val charset: java.nio.charset.Charset, val text: String)

    private sealed class Found {
        data class Line(val lock: Lock) : Found()
        data class Script(val page: ScriptPage) : Found()
    }

    private companion object {
        const val CONTEXT = 0x400L
        const val MAX_HITS = 64
        const val MAX_POINTERS = 16
        const val LINE_BYTES = 512
        /** How far past a found line to look for the next one. */
        const val TABLE_PEEK = 1024
        const val POLL_MS = 250L
        const val RETRY_MS = 10_000L
        /** How far around the last page the next one is looked for: scenes jump, but not far. */
        const val SCRIPT_WINDOW = 64 * 1024L
        /** How much of a script around a search hit is split into pages. */
        const val PAGE_CONTEXT = 4 * 1024L
        const val PAGE_SIMILARITY = 0.6
        const val SCRIPT_MISSES = 3
    }
}
