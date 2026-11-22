package app.genkan.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EntryRetryTest {
    /** Answers as the catalog would, one per ask: found, not listed, failed or bad. */
    private class Catalog(vararg answers: String) {
        private val queue = ArrayDeque(answers.toList())
        val asked = mutableListOf<Long>()
        suspend fun ask(now: Long): String = queue.removeFirst().also { asked += now }
    }

    /** Only a failed fetch is asked again; a bad entry is a failure a retry won't fix. */
    private val again: (String) -> Boolean = { it.startsWith("failed") }

    @Test
    fun `an id the catalog doesn't list is asked once, at once`() = runTest {
        val catalog = Catalog("not listed")
        assertEquals("not listed", EntryRetry.retrying(ask = { catalog.ask(currentTime) }, again = again))
        assertEquals(listOf(0L), catalog.asked)
    }

    @Test
    fun `a fetch that fails three times and then works is found`() = runTest {
        val catalog = Catalog("failed: offline", "failed: offline", "failed: slow", "found")
        assertEquals("found", EntryRetry.retrying(ask = { catalog.ask(currentTime) }, again = again))
        assertEquals(listOf(0L, 5_000L, 20_000L, 60_000L), catalog.asked)
    }

    @Test
    fun `four failures give up at a minute with the last reason`() = runTest {
        val catalog = Catalog("failed: offline", "failed: offline", "failed: offline", "failed: the server answered 503")
        assertEquals("failed: the server answered 503", EntryRetry.retrying(ask = { catalog.ask(currentTime) }, again = again))
        assertEquals(listOf(0L, 5_000L, 20_000L, 60_000L), catalog.asked)
    }

    @Test
    fun `a bad entry is asked once`() = runTest {
        val catalog = Catalog("bad entry")
        assertEquals("bad entry", EntryRetry.retrying(ask = { catalog.ask(currentTime) }, again = again))
        assertEquals(listOf(0L), catalog.asked)
    }
}
