package app.genkan.manual

import app.genkan.library.GameEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.IOException

/** What the control pressed shows while a manual is fetched, after one fails, and after one is cancelled. */
class ManualFetcherTest {
    /** A source whose fetches report what the test says and wait for [answer]. */
    private class Source : ManualSource {
        var cachedFile: File? = null
        var fetches = 0
        var answer = CompletableDeferred<Result<File>>()
        var report: ((Float?) -> Unit)? = null
        /** Whether the fetch's wait goes on through a cancel, as a blocking read on the IO thread does. */
        var uncancellable = false

        override fun has(game: GameEntity) = true
        override fun cached(game: GameEntity) = cachedFile
        override suspend fun file(game: GameEntity, onProgress: (Float?) -> Unit): File {
            fetches++
            cachedFile?.let { return it }
            report = onProgress
            onProgress(0f)
            val result = if (uncancellable) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { answer.await() } else answer.await()
            return result.getOrThrow()
        }
    }

    private val game = GameEntity(1, 2, "psx", "A game", "a game", "a.chd", 10, null, false, manualPath = "/m.pdf")
    private val file = File("manual.pdf")

    private class Setup(scope: TestScope) {
        val source = Source()
        val opened = mutableListOf<File>()
        val failures = mutableListOf<Throwable>()
        val fetcher = ManualFetcher(source, scope.backgroundScope, failedLabel = "Couldn't fetch the manual")
        fun open(game: GameEntity) = fetcher.open(game, onFile = { opened += it }, onFailed = { failures += it })
    }

    @Test
    fun `a fetch shows busy with how far along it is, then nothing once the file opens`() = runTest {
        val s = Setup(this)
        s.open(game)
        runCurrent()
        assertEquals(ManualFetch.Busy(0f), s.fetcher.state.value)
        s.source.report!!(0.6f)
        assertEquals(ManualFetch.Busy(0.6f), s.fetcher.state.value)
        s.source.answer.complete(Result.success(file))
        runCurrent()
        assertNull(s.fetcher.state.value)
        assertEquals(listOf(file), s.opened)
    }

    @Test
    fun `a cached manual opens without ever showing busy`() = runTest {
        val s = Setup(this)
        s.source.cachedFile = file
        val seen = mutableListOf<ManualFetch?>()
        s.open(game)
        seen += s.fetcher.state.value
        runCurrent()
        seen += s.fetcher.state.value
        assertEquals(listOf<ManualFetch?>(null, null), seen)
        assertEquals(listOf(file), s.opened)
    }

    @Test
    fun `a failure shows for a few seconds, with why for the caller`() = runTest {
        val s = Setup(this)
        s.open(game)
        runCurrent()
        s.source.answer.complete(Result.failure(IOException("the server answered 500")))
        runCurrent()
        assertEquals(ManualFetch.Failed("Couldn't fetch the manual"), s.fetcher.state.value)
        assertEquals("the server answered 500", s.failures.single().message)
        advanceTimeBy(ManualFetcher.FAILED_MS + 1)
        assertNull(s.fetcher.state.value)
    }

    @Test
    fun `a press while the failure shows fetches again at once`() = runTest {
        val s = Setup(this)
        s.open(game)
        runCurrent()
        s.source.answer.complete(Result.failure(IOException("offline")))
        runCurrent()
        s.source.answer = CompletableDeferred()
        s.open(game)
        runCurrent()
        assertEquals(2, s.source.fetches)
        assertEquals(ManualFetch.Busy(0f), s.fetcher.state.value)
    }

    @Test
    fun `a press while busy doesn't fetch again`() = runTest {
        val s = Setup(this)
        s.open(game)
        runCurrent()
        s.open(game)
        runCurrent()
        assertEquals(1, s.source.fetches)
    }

    @Test
    fun `a cancelled fetch shows nothing, whatever it still reports or throws`() = runTest {
        val s = Setup(this)
        s.open(game)
        runCurrent()
        val late = s.source.report!!
        s.fetcher.cancel()
        assertNull(s.fetcher.state.value)
        // The copy runs on for a while (blocking, on another thread) and reports, then ends.
        late(0.9f)
        s.source.answer.complete(Result.failure(CancellationException("cancelled")))
        runCurrent()
        advanceTimeBy(ManualFetcher.FAILED_MS + 1)
        assertNull(s.fetcher.state.value)
        assertEquals(emptyList<Throwable>(), s.failures)
        assertEquals(emptyList<File>(), s.opened)
    }

    @Test
    fun `a fetch that fails after it was cancelled says nothing`() = runTest {
        val s = Setup(this)
        s.source.uncancellable = true
        s.open(game)
        runCurrent()
        s.fetcher.cancel()
        // The stalled read times out after the cancel: the failure reaches the fetcher anyway.
        s.source.answer.complete(Result.failure(IOException("timeout")))
        runCurrent()
        advanceTimeBy(ManualFetcher.FAILED_MS + 1)
        assertNull(s.fetcher.state.value)
        assertEquals(emptyList<Throwable>(), s.failures)
        assertEquals(emptyList<File>(), s.opened)
    }
}
