package app.genkan.core

import android.util.Log
import app.genkan.yomikata.CharTable
import app.genkan.yomikata.CleanupStep
import app.genkan.yomikata.LayoutRead
import app.genkan.yomikata.LayoutError
import app.genkan.yomikata.LayoutMemory
import app.genkan.yomikata.Layouts
import com.swordfish.libretrodroid.GenkanTap
import org.json.JSONObject

/**
 * A Yomikata layout read between frames, in the player's own process: on the emulation thread
 * right after the core runs a frame, so memory is never caught half-written. Every frame, or
 * at the layout's poll rate; what it reads goes to [report] only when it changes.
 */
class LayoutWatch(private val core: CoreDef, private val report: (String) -> Unit) {
    private class Watched(
        /** A version's layouts, in the entry's order; each read takes the first whose `when` is true. */
        val layouts: List<JSONObject>,
        val table: CharTable?,
        val unit: Int,
        val controls: Map<String, String>,
        val cleanup: List<CleanupStep>,
        val every: Int,
        /** The entry's tables then a set's files, built once per set; null when one is missing. */
        val withSet: (List<String>) -> CharTable?,
    )

    @Volatile private var watched: Watched? = null
    private var frame = 0L
    private var last: LayoutRead? = null
    private val gate = WhenGate()

    /**
     * Starts reading [layoutJson] (empty: stops), with what the entry adds ([entryJson]: unit,
     * controls, cleanup) and its table; whether it could.
     */
    fun set(layoutJson: String, tableText: String, entryJson: String): Boolean {
        if (layoutJson.isBlank()) {
            watched = null
            return true
        }
        return try {
            val layouts = Layouts.parseLayouts(layoutJson)
            val layout = layouts.first()
            val entry = entryJson.takeIf { it.isNotBlank() }?.let { JSONObject(it) } ?: JSONObject()
            val poll = layout.opt("poll")
            // "frame", or a rate in Hz: the frames between reads at 60 a second.
            val every = (poll as? Number)?.toInt()?.takeIf { it > 0 }?.let { maxOf(1, FRAMES_PER_SECOND / it) } ?: 1
            val table = tableText.takeIf { it.isNotBlank() }?.let { CharTable.parse(listOf(it)) }
            val controls = entry.optJSONObject("controls")?.let { c -> c.keys().asSequence().associate { it.uppercase() to c.getString(it) } }.orEmpty()
            val setTexts = entry.optJSONObject("set_tables")?.let { s -> s.keys().asSequence().associateWith { s.getString(it) } }.orEmpty()
            val sets = HashMap<List<String>, CharTable?>()
            val withSet = { paths: List<String> ->
                sets.getOrPut(paths) {
                    val files = paths.map { setTexts[it] }
                    if (files.any { it == null }) null else CharTable.parse(listOfNotNull(tableText.takeIf { it.isNotBlank() }) + files.filterNotNull())
                }
            }
            last = null
            gate.reset()
            watched = Watched(layouts, table, entry.optInt("unit", 2), controls, CleanupStep.parse(entry.optJSONArray("cleanup")), every, withSet)
            Log.i(TAG, "Watching ${layouts.size} layout(s) every $every frame(s)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't take the layout: $e")
            watched = null
            false
        }
    }

    /** After each frame. Nothing here may throw into the emulation thread's loop. */
    fun afterFrame() {
        val w = watched ?: return
        if (frame++ % w.every != 0L) return
        try {
            // The read right after the layout's `when` turns true can still hold what the memory
            // held before (a movie's data): it waits for the next.
            val layout = Layouts.liveOf(w.layouts, memory) ?: return
            if (!gate.admit(Layouts.live(layout, memory))) return
            // Which of the version's layouts read it: the backlog tells a layout handing over from a repeat.
            val read = Layouts.read(layout, memory, w.unit, w.table, w.controls, w.cleanup, w.withSet).copy(layout = w.layouts.indexOf(layout))
            // Compared as values; turned into JSON only when it changed.
            if (read == last) return
            last = read
            report(Layouts.toJson(read).toString())
        } catch (e: Exception) {
            Log.w(TAG, "Stopped watching the layout: $e")
            watched = null
            // A blank report tells the reader the layout no longer gives the lines.
            report("")
        }
    }

    /** The game's addresses, folded through its mirrors to where the core keeps the memory. */
    private val memory = LayoutMemory { address, size ->
        // A zero-length read (a length field between lines) is empty text, not a failure.
        if (size == 0) return@LayoutMemory ByteArray(0)
        val local = (address and core.pointerMask) - core.ramBase
        val out = ByteArray(size)
        if (local < 0 || !GenkanTap.readMemory(local, out, size)) throw LayoutError("0x%08X+%d is outside memory".format(address, size))
        out
    }

    private companion object {
        const val TAG = "LayoutWatch"
        const val FRAMES_PER_SECOND = 60
    }
}

/**
 * A layout's `when` as it changes from read to read: nothing is taken from the first read after
 * it turns from false to true, only from the second running on. The game can switch its mode a
 * frame before it clears the memory the layout reads (Memories Off 2nd, coming out of a movie).
 */
class WhenGate {
    private var wasLive: Boolean? = null

    /** Whether a read is taken, given the layout's `when` now ([live]; null: it has none). */
    fun admit(live: Boolean?): Boolean {
        val before = wasLive
        wasLive = live
        return !(live == true && before == false)
    }

    fun reset() {
        wasLive = null
    }
}
