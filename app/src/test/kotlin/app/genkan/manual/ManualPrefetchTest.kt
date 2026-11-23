package app.genkan.manual

import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadState
import app.genkan.library.GameEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ManualPrefetchTest {
    @get:Rule val temp = TemporaryFolder()

    private fun game(id: Long, manual: Boolean = true) = GameEntity(
        id, 1, "psx", "Game $id", "game $id", "g$id.chd", 10, null, false, manualPath = if (manual) "/m/$id.pdf" else null,
    )

    @Test
    fun `installed means INSTALLED with its file on the device`() {
        val here = temp.newFile("here.chd")
        val rows = listOf(
            DownloadEntity(1, "a", "psx", DownloadState.INSTALLED, path = here.path, queuedAt = 0),
            DownloadEntity(2, "b", "psx", DownloadState.INSTALLED, path = temp.root.resolve("gone.chd").path, queuedAt = 0),
            DownloadEntity(3, "c", "psx", DownloadState.RUNNING, path = here.path, queuedAt = 0),
        )
        assertEquals(listOf(1L), ManualPrefetch.installed(rows))
    }

    @Test
    fun `one at a time, a cached manual is checked, one not cached is fetched, a game without one is left`() = runTest {
        val done = mutableListOf<String>()
        val fetched = ManualPrefetch.prefetchAll(
            listOf(game(1), game(2, manual = false), game(3)),
            awaitFree = {}, metered = { false },
            check = { done += "check ${it.id}" }, cached = { it.id == 1L },
            fetch = { done += "fetch ${it.id}"; true }, full = { false }, trim = {},
        )
        assertEquals(listOf("check 1", "fetch 3"), done)
        assertEquals(1, fetched)
    }

    @Test
    fun `it holds while Genkan isn't in front`() = runTest {
        val front = MutableStateFlow(false)
        val done = mutableListOf<Long>()
        val run = async {
            ManualPrefetch.prefetchAll(listOf(game(1)), awaitFree = { front.first { it } }, metered = { false }, check = {}, cached = { false }, fetch = { done += it.id; true }, full = { false }, trim = {})
        }
        advanceTimeBy(60 * 60 * 1000L)
        runCurrent()
        assertTrue(done.isEmpty())
        front.value = true
        assertEquals(1, run.await())
    }

    @Test
    fun `on a metered connection it stops`() = runTest {
        val fetched = ManualPrefetch.prefetchAll(listOf(game(1)), awaitFree = {}, metered = { true }, check = {}, cached = { false }, fetch = { true }, full = { false }, trim = {})
        assertEquals(0, fetched)
    }

    @Test
    fun `at the cap it trims, and stops when that doesn't make room`() = runTest {
        var trims = 0
        var size = 2
        val fetched = ManualPrefetch.prefetchAll(
            listOf(game(1), game(2), game(3)),
            awaitFree = {}, metered = { false }, check = {}, cached = { false },
            fetch = { size++; true }, full = { size >= 3 }, trim = { trims++ },
        )
        assertEquals(1, fetched)
        assertEquals(1, trims)
    }

    @Test
    fun `games gone from the library are removed only after a sync, and never for an empty library`() {
        val removed = mutableListOf<Set<Long>>()
        ManualPrefetch.housekeep(afterSync = false, libraryIds = setOf(1L), removeGone = { removed += it })
        assertTrue("a pass a download started", removed.isEmpty())
        ManualPrefetch.housekeep(afterSync = true, libraryIds = emptySet(), removeGone = { removed += it })
        assertTrue("an empty library", removed.isEmpty())
        ManualPrefetch.housekeep(afterSync = true, libraryIds = setOf(1L), removeGone = { removed += it })
        assertEquals(listOf(setOf(1L)), removed)
    }
}
