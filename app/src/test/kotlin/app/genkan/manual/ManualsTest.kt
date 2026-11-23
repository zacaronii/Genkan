package app.genkan.manual

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.library.GameEntity
import app.genkan.romm.RomMAccount
import app.genkan.romm.RomMSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** A game's manual from the server, and what's kept of it when the server can't be reached. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ManualsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val folder = File(context.cacheDir, "manuals")

    @Before fun start() {
        server.start()
        folder.deleteRecursively()
    }

    @After fun stop() {
        server.close()
        folder.deleteRecursively()
    }

    /** A test that waits on the network or a flow fails instead of hanging the run. */
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    private val session = mockk<RomMSession>(relaxed = true) {
        every { currentAccount() } answers { RomMAccount(server.url("/").toString(), "token", null, 1, "player", "4.0.0") }
        every { credentials() } answers { server.url("/").toString() to "token" }
    }
    private val manuals = Manuals(context, session, OkHttpClient())

    /** A game whose manual is at [path] on the server, the rom last updated at [version] (its `ts`, as LibrarySync gives it). */
    private fun game(version: String, path: String = "/assets/romm/resources/roms/1/7/manual/7.pdf") = GameEntity(
        7, 1, "psx", "A game", "a game", "a.chd", 10, null, false, manualPath = "$path?ts=$version",
    )

    private val other = "/assets/romm/resources/roms/1/7/manual/7-ja.pdf"

    /** A manual that was cached for [game] and is whole. */
    private fun cachedFile(game: GameEntity, text: String): File =
        File(folder, Manuals.fileName(game)).apply { parentFile?.mkdirs(); writeText(text) }

    /** A made-up PDF answer: what makes it one is how it starts. */
    private fun pdf(text: String, etag: String) = MockResponse.Builder().code(200).addHeader("ETag", etag).addHeader("Content-Type", "application/pdf").body("%PDF $text").build()

    /** A manual file's text, without the made-up PDF's first bytes. */
    private fun File.content() = readText().removePrefix("%PDF ")

    @Test
    fun `a manual that can't be fetched opens the game's earlier one, and keeps it`() = runBlocking {
        // The game's manual moved to another path: its earlier file still opens while the new one can't be had.
        val earlier = cachedFile(game("1"), "manual 1")
        // The server is away: neither the HEAD nor the fetch is answered.
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        val opened = manuals.file(game("2", path = other))
        assertEquals("manual 1", opened.content())
        assertTrue(opened.isFile)
    }

    @Test(expected = IOException::class)
    fun `with no earlier version, a failed fetch throws as before`() {
        runBlocking {
            server.enqueue(MockResponse.Builder().code(503).build())
            manuals.file(game("2"))
        }
    }

    @Test
    fun `a manual fetched from another path deletes the game's earlier one`() = runBlocking {
        val earlier = cachedFile(game("1"), "manual 1")
        // Another file, so another length: the HEAD doesn't take the earlier one for it.
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Length", "15").build())
        server.enqueue(pdf("the new manual!", "m2"))
        val opened = manuals.file(game("2", path = other))
        assertEquals("the new manual!", opened.content())
        assertTrue(!earlier.exists())
    }

    @Test
    fun `a sync's new ts doesn't fetch the manual again, and a cached one opens with no request`() = runBlocking {
        server.enqueue(pdf("manual", "m1"))
        val first = manuals.file(game("1"))
        assertEquals(1, server.requestCount)
        // The game changed on the server (its ts moved), not its manual.
        assertEquals(first, manuals.cached(game("2")))
        assertEquals(Manuals.Refresh.NOT_DUE, manuals.refresh(game("2")))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the daily check asks with the ETag, a 304 keeps the manual, and it isn't asked again that day`() = runBlocking {
        var clock = 1_000_000_000_000L
        manuals.now = { clock }
        server.enqueue(pdf("manual", "m1"))
        manuals.file(game("1"))
        clock += Manuals.CHECK_EVERY_MS + 1
        server.enqueue(MockResponse.Builder().code(304).build())
        assertEquals(Manuals.Refresh.UNCHANGED, manuals.refresh(game("1")))
        server.takeRequest()
        assertEquals("m1", server.takeRequest().headers["If-None-Match"])
        assertEquals(Manuals.Refresh.NOT_DUE, manuals.refresh(game("1")))
        assertEquals(2, server.requestCount)
        assertEquals("manual", manuals.cached(game("1"))!!.content())
    }

    @Test
    fun `a manual replaced on the server is fetched by the check, takes the cached one's place, and is reported`() = runBlocking {
        var clock = 1_000_000_000_000L
        manuals.now = { clock }
        server.enqueue(pdf("English manual", "en"))
        manuals.file(game("1"))
        val reported = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { manuals.replaced.first() }
        clock += Manuals.CHECK_EVERY_MS + 1
        server.enqueue(pdf("Japanese manual", "ja"))
        assertEquals(Manuals.Refresh.REPLACED, manuals.refresh(game("1")))
        assertEquals("Japanese manual", manuals.cached(game("1"))!!.content())
        assertEquals(7L, reported.await())
    }

    @Test
    fun `offline, the check changes nothing and the cached manual still opens`() = runBlocking {
        var clock = 1_000_000_000_000L
        manuals.now = { clock }
        server.enqueue(pdf("manual", "m1"))
        val file = manuals.file(game("1"))
        clock += Manuals.CHECK_EVERY_MS + 1
        server.close()
        assertEquals(Manuals.Refresh.FAILED, manuals.refresh(game("1")))
        assertEquals("manual", file.content())
        assertEquals(file, manuals.file(game("1")))
    }

    @Test
    fun `a manual cached under the earlier name is kept, after a HEAD finds the server's of its length`() = runBlocking {
        // How it was named before: with the rom's ts in the hash.
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest("${game("1").manualPath}|10".toByteArray())
        val oldName = "7-${digest.take(6).joinToString("") { "%02x".format(it) }}.pdf"
        File(folder, oldName).apply { parentFile?.mkdirs(); writeText("manual") }
        server.enqueue(MockResponse.Builder().code(200).addHeader("ETag", "m1").addHeader("Content-Length", "6").build())
        val opened = manuals.file(game("1"))
        assertEquals("manual", opened.content())
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("no download", 1, server.requestCount)
        // Kept under its new name, with what's needed to ask about it later.
        assertEquals(opened, manuals.cached(game("2")))
    }

    @Test
    fun `a fetch says how far along it is, from the length the server gives`() = runBlocking {
        val body = "%PDF" + "x".repeat(200_000)
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
        val seen = mutableListOf<Float?>()
        manuals.file(game("2")) { seen += it }
        assertEquals(0f, seen.first())
        assertEquals(1f, seen.last())
        assertTrue(seen.filterNotNull().zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `a fetch cancelled mid-copy stops and leaves no file`() = runBlocking {
        // A slow download: 1 MB at 64 KB every 50 ms.
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(1_000_000)).throttleBody(64 * 1024, 50, java.util.concurrent.TimeUnit.MILLISECONDS).build())
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fetch = launch(kotlinx.coroutines.Dispatchers.Default) { manuals.file(game("2")) { if (it != null && it > 0f) started.complete(Unit) } }
        started.await()
        fetch.cancelAndJoin()
        assertEquals(emptyList<String>(), folder.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `a stalled fetch ends at once when cancelled, not at its timeout`() = runBlocking {
        // 100 bytes, then nothing for 10 s: the next read blocks until the call is cancelled.
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(1_000)).throttleBody(100, 10, java.util.concurrent.TimeUnit.SECONDS).build())
        val reading = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fetch = launch(kotlinx.coroutines.Dispatchers.Default) { manuals.file(game("2")) { if (it != null && it > 0f) reading.complete(Unit) } }
        reading.await()
        val started = System.nanoTime()
        fetch.cancelAndJoin()
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 2_000)
        assertEquals(emptyList<String>(), folder.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `a cached manual opens at once while a check of it waits on the server`() = runBlocking {
        var clock = 1_000_000_000_000L
        manuals.now = { clock }
        server.enqueue(pdf("manual", "m1"))
        manuals.file(game("1"))
        clock += Manuals.CHECK_EVERY_MS + 1
        // The check's answer is held back, as on a slow connection.
        server.enqueue(MockResponse.Builder().code(304).headersDelay(5, java.util.concurrent.TimeUnit.SECONDS).build())
        val check = launch(kotlinx.coroutines.Dispatchers.IO) { manuals.refresh(game("1")) }
        kotlinx.coroutines.delay(200)
        val started = System.currentTimeMillis()
        val opened = manuals.file(game("1"))
        assertTrue("opened in ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 1_000)
        assertEquals("manual", opened.content())
        check.join()
    }

    @Test
    fun `a page instead of a manual never replaces the cached one`() = runBlocking {
        var clock = 1_000_000_000_000L
        manuals.now = { clock }
        server.enqueue(pdf("manual", "m1"))
        manuals.file(game("1"))
        clock += Manuals.CHECK_EVERY_MS + 1
        // A proxy's sign-in page, answered with a 200.
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html>Sign in</html>").build())
        assertEquals(Manuals.Refresh.FAILED, manuals.refresh(game("1")))
        assertEquals("manual", manuals.cached(game("1"))!!.content())
        // Said to be a PDF, but isn't one.
        server.enqueue(MockResponse.Builder().code(200).addHeader("ETag", "m2").addHeader("Content-Type", "application/pdf").body("not a pdf").build())
        assertEquals(Manuals.Refresh.FAILED, manuals.refresh(game("1")))
        assertEquals("manual", manuals.cached(game("1"))!!.content())
        assertTrue("no part left", folder.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test(expected = IOException::class)
    fun `a first fetch that brings a page instead of a manual keeps nothing`() {
        runBlocking {
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html>Sign in</html>").build())
            try {
                manuals.file(game("1"))
            } finally {
                assertEquals(null, manuals.cached(game("1")))
            }
        }
    }

    @Test
    fun `a manual with no validator is checked by a HEAD, and kept when the length is the same`() = runBlocking {
        var clock = 1_000_000_000_000L
        manuals.now = { clock }
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/pdf").body("%PDF manual").build())
        manuals.file(game("1"))
        clock += Manuals.CHECK_EVERY_MS + 1
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Length", "11").build())
        assertEquals(Manuals.Refresh.UNCHANGED, manuals.refresh(game("1")))
        server.takeRequest()
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("no download", 2, server.requestCount)
    }
}
