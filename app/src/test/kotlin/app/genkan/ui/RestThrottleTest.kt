package app.genkan.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The bottom screen following the shelf's focus ([DETAILS_FOLLOW_MS]). */
@OptIn(ExperimentalCoroutinesApi::class)
class RestThrottleTest {
    private val window = DETAILS_FOLLOW_MS

    /** Sends [steps] (value, ms after the start) and returns what came out, with when. */
    private fun run(vararg steps: Pair<Int, Long>): List<Pair<Int, Long>> {
        val out = mutableListOf<Pair<Int, Long>>()
        runTest {
            val focus = MutableSharedFlow<Int>(extraBufferCapacity = 64)
            val job = launch { focus.restThrottle(window, now = { testScheduler.currentTime }).collect { out += it to testScheduler.currentTime } }
            runCurrent()
            var now = 0L
            for ((value, at) in steps) {
                advanceTimeBy(at - now); runCurrent(); now = at
                focus.emit(value); runCurrent()
            }
            advanceTimeBy(1_000); runCurrent()
            job.cancel()
        }
        return out
    }

    @Test
    fun `a single press after a rest shows at once`() {
        assertEquals(listOf(1 to 0L, 2 to 1_000L), run(1 to 0L, 2 to 1_000L))
    }

    @Test
    fun `of two presses 50 ms apart the first shows at once, the second at rest`() {
        assertEquals(listOf(1 to 1_000L, 2 to 1_050L + window), run(0 to 0L, 1 to 1_000L, 2 to 1_050L).drop(1))
    }

    @Test
    fun `a held D-pad shows its press, its first repeat and its last step only`() {
        // The press at 1 s, the first repeat 400 ms later (a rest longer than the window, so it
        // shows too), then a repeat every 50 ms to 2 s: those wait for the end.
        val hold = listOf(1 to 1_000L) + (0 until 13).map { 2 + it to 1_400L + 50L * it }
        val out = run(0 to 0L, *hold.toTypedArray())
        assertEquals(listOf(0 to 0L, 1 to 1_000L, 2 to 1_400L, 14 to 2_000L + window), out)
    }

    @Test
    fun `a press exactly the window after the last change shows at once`() {
        assertEquals(listOf(1 to 1_000L, 2 to 1_000L + window), run(0 to 0L, 1 to 1_000L, 2 to 1_000L + window).drop(1))
    }

    /** Steps (value, ms, from a repeat) through the throttle as the library sends them; what came out, with when. */
    private fun runKeys(vararg steps: Triple<Int, Long, Boolean>): List<Pair<Int, Long>> {
        val out = mutableListOf<Pair<Int, Long>>()
        runTest {
            val focus = MutableSharedFlow<Pair<Int, Boolean>>(extraBufferCapacity = 64)
            val job = launch {
                focus.restThrottle(window, now = { testScheduler.currentTime }, atOnce = { !it.second })
                    .collect { out += it.first to testScheduler.currentTime }
            }
            runCurrent()
            var now = 0L
            for ((value, at, repeat) in steps) {
                advanceTimeBy(at - now); runCurrent(); now = at
                focus.emit(value to repeat); runCurrent()
            }
            advanceTimeBy(1_000); runCurrent()
            job.cancel()
        }
        return out
    }

    /** A held D-pad from [at]: the press, the first repeat 400 ms later, then one every 50 ms, [repeats] in all. */
    private fun hold(first: Int, at: Long, repeats: Int) =
        listOf(Triple(first, at, false)) + (0 until repeats).map { Triple(first + 1 + it, at + 400 + 50L * it, true) }

    @Test
    fun `a hold's first repeat waits, so a hold is two updates`() {
        val out = runKeys(Triple(0, 0L, false), *hold(1, 1_000, 13).toTypedArray())
        // The press at once, then only the last step, once the hold has rested.
        assertEquals(listOf(0 to 0L, 1 to 1_000L, 14 to 1_400L + 50 * 12 + window), out)
    }

    @Test
    fun `a fresh press a full rest after a hold shows at once`() {
        val steps = hold(1, 1_000, 5) + Triple(10, 1_600L + window, false)
        assertEquals(listOf(10 to 1_600L + window), runKeys(Triple(0, 0L, false), *steps.toTypedArray()).filter { it.first == 10 })
    }

    @Test
    fun `a fresh press less than the rest after a hold's last repeat waits`() {
        val steps = hold(1, 1_000, 5) + Triple(10, 1_650L, false)
        assertEquals(listOf(10 to 1_650L + window), runKeys(Triple(0, 0L, false), *steps.toTypedArray()).filter { it.first == 10 })
    }
}
