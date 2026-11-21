package app.genkan.core

import android.util.Log
import app.genkan.yomikata.CharTable
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
    private class Watched(val layout: JSONObject, val table: CharTable?, val unit: Int, val every: Int)

    @Volatile private var watched: Watched? = null
    private var frame = 0L
    private var last: String? = null

    /** Starts reading [layoutJson] (empty: stops). */
    fun set(layoutJson: String, tableText: String, unit: Int) {
        if (layoutJson.isBlank()) {
            watched = null
            return
        }
        val layout = JSONObject(layoutJson)
        val poll = layout.opt("poll")
        // "frame", or a rate in Hz: the frames between reads at 60 a second.
        val every = (poll as? Number)?.toInt()?.takeIf { it > 0 }?.let { maxOf(1, FRAMES_PER_SECOND / it) } ?: 1
        val table = tableText.takeIf { it.isNotBlank() }?.let { CharTable.parse(listOf(it)) }
        last = null
        watched = Watched(layout, table, unit, every)
        Log.i(TAG, "Watching a layout every $every frame(s)")
    }

    /** After each frame. */
    fun afterFrame() {
        val w = watched ?: return
        if (frame++ % w.every != 0L) return
        val json = Layouts.toJson(Layouts.read(w.layout, memory, w.unit, w.table)).toString()
        if (json == last) return
        last = json
        report(json)
    }

    /** The game's addresses, folded through its mirrors to where the core keeps the memory. */
    private val memory = LayoutMemory { address, size ->
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
