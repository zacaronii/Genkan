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
    private val folder = File(context.noBackupFilesDir, "manuals")
    private val oldFolder = File(context.cacheDir, "manuals")
    private val signedOut = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Before fun start() {
        server.start()
        folder.deleteRecursively()
        oldFolder.deleteRecursively()
    }

    @After fun stop() {
        server.close()
        folder.deleteRecursively()
        oldFolder.deleteRecursively()
    }

    /** A test that waits on the network or a flow fails instead of hanging the run. */
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    private val session = mockk<RomMSession>(relaxed = true) {
        every { currentAccount() } answers { RomMAccount(server.url("/").toString(), "token", null, 1, "player", "4.0.0") }
        every { signedOut } returns this@ManualsTest.signedOut
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

    private fun gameNumbered(id: Long) = GameEntity(
        id, 1, "psx", "Game $id", "game $id", "g$id.chd", 10, null, false, manualPath = "/assets/romm/resources/roms/1/$id/manual/$id.pdf?ts=1",
    )

    /** A manual stored for [game], [bytes] long, last opened at [openedAt]. */
    private fun stored(game: GameEntity, bytes: Int, openedAt: Long): File {
        val file = File(folder, Manuals.fileName(game)).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)) }
        File(file.path + ".meta").writeText("e\n\n0\n$openedAt\n")
        return file
    }

    @Test
    fun `manuals move from the cache folder once, a half-done move finishes, and nothing else there is touched`() {
        val other = File(context.cacheDir, "other/keep.bin").apply { parentFile?.mkdirs(); writeText("x") }
        oldFolder.mkdirs()
        File(oldFolder, "1-aaa.pdf").writeText("one")
        File(oldFolder, "1-aaa.pdf.meta").writeText("m")
        File(oldFolder, "2-bbb.pdf").writeText("two")
        // Cut off halfway last time: one is already in the new folder.
        folder.mkdirs()
        File(folder, "2-bbb.pdf").writeText("two")
        manuals.moveFromCache()
        assertEquals(setOf("1-aaa.pdf", "1-aaa.pdf.meta", "2-bbb.pdf"), folder.list()!!.toSet())
        assertEquals("one", File(folder, "1-aaa.pdf").readText())
        assertTrue(!oldFolder.exists())
        assertEquals("x", other.readText())
        other.parentFile!!.deleteRecursively()
    }

    @Test
    fun `manuals of games gone from the library go, never one in use`() {
        val kept = stored(gameNumbered(1), 10, 0)
        val gone = stored(gameNumbered(2), 10, 0)
        val open = stored(gameNumbered(3), 10, 0)
        manuals.use("details", open)
        manuals.removeGone(setOf(1L))
        assertTrue(kept.exists())
        assertTrue(!gone.exists() && !File(gone.path + ".meta").exists())
        assertTrue("in use", open.exists())
    }

    @Test
    fun `over the cap, games that aren't installed lose theirs, least recently opened first, never installed or in use`() {
        val installed = stored(gameNumbered(1), 1000, 1)
        val oldest = stored(gameNumbered(2), 1000, 2)
        val inUse = stored(gameNumbered(3), 1000, 3)
        val newer = stored(gameNumbered(4), 1000, 4)
        val newest = stored(gameNumbered(5), 1000, 5)
        manuals.use("reader", inUse)
        // Opening it just now made it the newest; set its time back for the test.
        File(inUse.path + ".meta").writeText("e\n\n0\n3\n")
        manuals.trim(installed = setOf(1L), cap = 3200)
        assertTrue(installed.exists())
        assertTrue("the least recently opened goes first", !oldest.exists())
        assertTrue(inUse.exists())
        assertTrue("then the next", !newer.exists())
        assertTrue("under the cap now", newest.exists())
    }

    @Test
    fun `a manual in use is never replaced, and nothing is asked while it's open`() = runBlocking {
        var clock = 1_000_000_000_000L
        manuals.now = { clock }
        server.enqueue(pdf("English", "en"))
        val file = manuals.file(game("1"))
        manuals.use("reader", file)
        clock += Manuals.CHECK_EVERY_MS + 1
        assertEquals(Manuals.Refresh.NOT_DUE, manuals.refresh(game("1")))
        assertEquals("nothing asked", 1, server.requestCount)
        assertEquals("English", file.content())
    }

    @Test
    fun `a replaced manual says so once, on its next open`() = runBlocking {
        var clock = 1_000_000_000_000L
        manuals.now = { clock }
        server.enqueue(pdf("English", "en"))
        manuals.file(game("1"))
        assertTrue(!manuals.takeReplacedNote(game("1")))
        clock += Manuals.CHECK_EVERY_MS + 1
        server.enqueue(pdf("Japanese", "ja"))
        manuals.refresh(game("1"))
        assertTrue(manuals.takeReplacedNote(game("1")))
        assertTrue("once", !manuals.takeReplacedNote(game("1")))
    }

    @Test
    fun `a manual opened by hand is fetched, and the housekeeping runs after`() = runBlocking {
        var stored = 0
        manuals.onStored = { stored++ }
        server.enqueue(pdf("manual", "m1"))
        manuals.file(game("1"))
        assertEquals(1, stored)
    }

    @Test
    fun `signing out empties the folder`() = runBlocking {
        server.enqueue(pdf("manual", "m1"))
        manuals.file(game("1"))
        signedOut.emit(Unit)
        kotlinx.coroutines.withTimeout(5_000) { while (folder.list().orEmpty().isNotEmpty()) kotlinx.coroutines.delay(20) }
        assertEquals(null, manuals.cached(game("1")))
    }

    @Test
    fun `fetching ahead leaves out a manual over the limit, and one already cached`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Length", "${100L shl 20}").build())
        assertTrue(!manuals.prefetch(game("1"), maxBytes = 64L shl 20))
        assertEquals("only the HEAD", 1, server.requestCount)
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Length", "13").build())
        server.enqueue(pdf("manual", "m1"))
        assertTrue(manuals.prefetch(game("1"), maxBytes = 64L shl 20))
        assertTrue("cached now: nothing to do", !manuals.prefetch(game("1"), maxBytes = 64L shl 20))
    }

    @Test
    fun `an empty library removes nothing`() {
        val kept = stored(gameNumbered(1), 10, 0)
        manuals.removeGone(emptySet())
        assertTrue(kept.exists())
    }

    @Test
    fun `a check skipped while a manual is open runs when it's closed, and the next open says it's newer`() = runBlocking {
        var clock = 1_000_000_000_000L
        manuals.now = { clock }
        server.enqueue(pdf("English", "en"))
        val file = manuals.file(game("1"))
        manuals.use("details", file)
        clock += Manuals.CHECK_EVERY_MS + 1
        assertEquals(Manuals.Refresh.NOT_DUE, manuals.refresh(game("1")))
        server.enqueue(pdf("Japanese", "ja"))
        // Closed: the skipped check runs, in the background.
        manuals.use("details", null)
        kotlinx.coroutines.withTimeout(10_000) { while (file.content() != "Japanese") kotlinx.coroutines.delay(20) }
        assertTrue(manuals.takeReplacedNote(game("1")))
    }

    @Test
    fun `a manual still in the old folder is found by the very first lookup, with no request`() {
        val fresh = Manuals(context, session, okhttp3.OkHttpClient())
        File(oldFolder, Manuals.fileName(game("1"))).apply { parentFile?.mkdirs(); writeText("%PDF manual") }
        val found = fresh.cached(game("1"))
        assertEquals("manual", found?.content())
        assertEquals(0, server.requestCount)
    }
}
