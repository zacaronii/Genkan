package app.genkan.core

import java.io.File

/**
 * How busy each of the player's own threads is, from /proc/self/task: a threaded core's frame
 * waits on its workers, so the busiest thread (not the frame's time) says how much room is left.
 * Each [sample] is against the one before it.
 */
class ThreadLoad(private val ticksPerSecond: Long = TICKS) {

    /** A thread, and how much of one CPU it used since the last sample (1.0: all of it). */
    data class Busy(val tid: Int, val name: String, val share: Double)

    private var last: Map<Int, Long> = emptyMap()

    /** The threads that used any CPU since the last sample, busiest first; empty the first time. */
    fun sample(elapsedNanos: Long): List<Busy> {
        val now = HashMap<Int, Pair<String, Long>>()
        File("/proc/self/task").listFiles()?.forEach { task ->
            val tid = task.name.toIntOrNull() ?: return@forEach
            runCatching { File(task, "stat").readText() }.getOrNull()?.let(::parseStat)?.let { now[tid] = it }
        }
        return busiest(last, now, elapsedNanos).also { last = now.mapValues { it.value.second } }
    }

    /** Each thread's share of one CPU between [before] (ticks by tid) and [now] (name and ticks by tid), busiest first. */
    internal fun busiest(before: Map<Int, Long>, now: Map<Int, Pair<String, Long>>, elapsedNanos: Long): List<Busy> {
        if (before.isEmpty() || elapsedNanos <= 0) return emptyList()
        val seconds = elapsedNanos / 1e9
        return now.mapNotNull { (tid, value) ->
            val (name, ticks) = value
            val used = ticks - (before[tid] ?: return@mapNotNull null)
            if (used <= 0) null else Busy(tid, name, used.toDouble() / ticksPerSecond / seconds)
        }.sortedByDescending { it.share }
    }

    companion object {
        /** USER_HZ: Android's kernels count CPU time in hundredths of a second. */
        const val TICKS = 100L

        /** A task's name and its user plus system time in ticks, from its stat line; null when it doesn't read. */
        internal fun parseStat(line: String): Pair<String, Long>? {
            // The name is in parentheses and may hold spaces or parentheses of its own.
            val open = line.indexOf('(')
            val close = line.lastIndexOf(')')
            if (open < 0 || close < open) return null
            val fields = line.substring(close + 2).split(' ')
            // After the name: state is field 3, utime 14 and stime 15, so 11 and 12 from here.
            val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
            val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
            return line.substring(open + 1, close) to utime + stime
        }
    }
}
