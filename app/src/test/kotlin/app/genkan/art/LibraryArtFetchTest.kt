package app.genkan.art

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryArtFetchTest {
    private val paths = (1..6).map { "/c/$it.png" }

    @Test
    fun `every file in order, leaving out what was done within the day`() = runTest {
        val fetched = mutableListOf<String>()
        val count = LibraryArtFetch.fetchAll(paths, shelfBusy = { false }, fill = { 0.1 }, recentlyDone = { it == "/c/3.png" }) { fetched += it; true }
        assertEquals(paths - "/c/3.png", fetched)
        assertEquals(5, count)
    }

    @Test
    fun `it waits while the opened shelf is being fetched`() = runTest {
        var busy = true
        val fetched = mutableListOf<String>()
        val run = async { LibraryArtFetch.fetchAll(paths, shelfBusy = { busy }, fill = { 0.1 }, recentlyDone = { false }) { fetched += it; true } }
        advanceTimeBy(10 * LibraryArtFetch.BUSY_WAIT_MS)
        runCurrent()
        assertTrue("nothing while the shelf's own fetch runs", fetched.isEmpty())
        busy = false
        assertEquals(6, run.await())
    }

    @Test
    fun `it stops when the cache is nearly full, so nothing the screens drew is pushed out`() = runTest {
        val fetched = mutableListOf<String>()
        var fill = 0.85
        LibraryArtFetch.fetchAll(paths, shelfBusy = { false }, fill = { fill }, recentlyDone = { false }) { fetched += it; fill += 0.03; true }
        assertEquals(listOf("/c/1.png", "/c/2.png"), fetched)
    }
}
