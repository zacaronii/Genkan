package app.genkan.ui

import android.util.Log
import app.genkan.BuildConfig

/**
 * Counts what each step along the shelf costs, for measuring on the device. Off unless this is a
 * debug build and the tag is switched on, so a release build or a normal debug run pays nothing:
 *
 *     adb shell setprop log.tag.GenkanShelf DEBUG
 *     adb logcat -s GenkanShelf
 *
 * Each time the shelf recomposes (once per step) it logs the focus, how many cases recomposed
 * since the last line, how many covers loaded and from where, and how many measured proportions
 * wrote state. The flag is read when the shelf is first shown, so set it before opening the
 * library (or switch console to pick it up).
 */
internal object ShelfTrace {
    const val TAG = "GenkanShelf"

    private var cases = 0
    private var aspects = 0
    private var aspectWrites = 0
    private val loads = sortedMapOf<String, Int>()
    private var lastLog = 0L

    /** Whether to count; checked once per shelf, since the property lookup isn't free. */
    fun enabled(): Boolean = BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG)

    fun caseComposed() {
        cases++
    }

    fun coverLoaded(source: String) {
        loads[source] = (loads[source] ?: 0) + 1
    }

    fun aspectMeasured(stateWrite: Boolean) {
        aspects++
        if (stateWrite) aspectWrites++
    }

    /** Logs and resets the counts; called after each recomposition of the shelf. */
    fun shelfComposed(focus: Int) {
        val now = System.nanoTime()
        val sinceMs = if (lastLog == 0L) 0 else (now - lastLog) / 1_000_000
        lastLog = now
        Log.d(TAG, "focus=$focus +${sinceMs}ms cases=$cases covers=$loads aspects=$aspects stateWrites=$aspectWrites")
        cases = 0
        aspects = 0
        aspectWrites = 0
        loads.clear()
    }
}
