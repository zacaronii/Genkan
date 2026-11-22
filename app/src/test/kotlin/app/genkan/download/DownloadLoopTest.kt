package app.genkan.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** The service's loop around the queue (#18): it stops only once nothing is queued and no newer start came in. */
class DownloadLoopTest {
    private val queued = ArrayDeque<String>()
    private val done = mutableListOf<String>()
    private var latestStart = 1
    private val stops = mutableListOf<Int>()
    private var runs = 0

    private fun loop(stop: (Int) -> Boolean = { stops += it; true }, afterRun: (Int) -> Unit = {}) = DownloadLoop(
        runQueue = {
            runs++
            while (queued.isNotEmpty()) done += queued.removeFirst()
            afterRun(runs)
        },
        hasWork = { queued.isNotEmpty() },
        latestStart = { latestStart },
        stop = stop,
        main = EmptyCoroutineContext,
    )

    @Test
    fun `an empty queue runs once and stops with the latest start`() = runBlocking {
        queued += "a"
        loop().run()
        assertEquals(listOf("a"), done)
        assertEquals(1, runs)
        assertEquals(listOf(1), stops)
    }

    @Test
    fun `a game queued just as the queue ran dry is downloaded, not left queued`() = runBlocking {
        queued += "a"
        // After the queue's last look, another game is queued and asks for a start, which
        // launches nothing while this loop still runs.
        loop(afterRun = { run -> if (run == 1) { queued += "b"; latestStart = 2 } }).run()
        assertEquals(listOf("a", "b"), done)
        assertEquals(2, runs)
        assertEquals(listOf(2), stops)
    }

    @Test
    fun `a stop refused for a newer start goes round again`() = runBlocking {
        var refusals = 1
        loop(stop = { id -> stops += id; refusals-- <= 0 }).run()
        assertEquals(2, runs)
        assertEquals(listOf(1, 1), stops)
    }
}
