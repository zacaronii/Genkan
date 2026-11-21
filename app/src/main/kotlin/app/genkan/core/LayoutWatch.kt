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
        val layout: JSONObject,
        val table: CharTable?,
        val unit: Int,
        val controls: Map<String, String>,
        val cleanup: List<CleanupStep>,
        val every: Int,
    )

    @Volatile private var watched: Watched? = null
    private var frame = 0L
    private var last: LayoutRead? = null

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
            val layout = JSONObject(layoutJson)
            val entry = entryJson.takeIf { it.isNotBlank() }?.let { JSONObject(it) } ?: JSONObject()
            val poll = layout.opt("poll")
            // "frame", or a rate in Hz: the frames between reads at 60 a second.
            val every = (poll as? Number)?.toInt()?.takeIf { it > 0 }?.let { maxOf(1, FRAMES_PER_SECOND / it) } ?: 1
            val table = tableText.takeIf { it.isNotBlank() }?.let { CharTable.parse(listOf(it)) }
            val controls = entry.optJSONObject("controls")?.let { c -> c.keys().asSequence().associate { it.uppercase() to c.getString(it) } }.orEmpty()
            last = null
            watched = Watched(layout, table, entry.optInt("unit", 2), controls, CleanupStep.parse(entry.optJSONArray("cleanup")), every)
            Log.i(TAG, "Watching a layout every $every frame(s)")
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
            val read = Layouts.read(w.layout, memory, w.unit, w.table, w.controls, w.cleanup)
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
