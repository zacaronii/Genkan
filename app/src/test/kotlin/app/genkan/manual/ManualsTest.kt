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

    private val session = mockk<RomMSession>(relaxed = true) {
        every { currentAccount() } answers { RomMAccount(server.url("/").toString(), "token", null, 1, "player", "4.0.0") }
        every { credentials() } answers { server.url("/").toString() to "token" }
    }
    private val manuals = Manuals(context, session, OkHttpClient())

    /** A game whose manual is at version [version] on the server (its path carries it, as LibrarySync gives it). */
    private fun game(version: String) = GameEntity(
        7, 1, "psx", "A game", "a game", "a.chd", 10, null, false, manualPath = "/assets/romm/resources/roms/1/7/manual/7.pdf?ts=$version",
    )

    /** An earlier version's file in the folder, as an earlier fetch left it. */
    private fun earlierFile(version: String): File =
        File(folder, Manuals.fileName(game(version))).apply { parentFile?.mkdirs(); writeText("manual $version") }

    @Test
    fun `a manual that can't be fetched opens the game's earlier version, and keeps it`() = runBlocking {
        val earlier = earlierFile("1")
        server.enqueue(MockResponse.Builder().code(503).build())
        val opened = manuals.file(game("2"))
        assertEquals(earlier, opened)
        assertTrue(earlier.isFile)
        // The new version wasn't had, so it's still not cached: the next open tries the server again.
        assertEquals(null, manuals.cached(game("2")))
    }

    @Test(expected = IOException::class)
    fun `with no earlier version, a failed fetch throws as before`() {
        runBlocking {
            server.enqueue(MockResponse.Builder().code(503).build())
            manuals.file(game("2"))
        }
    }

    @Test
    fun `a manual fetched deletes the game's earlier versions`() = runBlocking {
        val earlier = earlierFile("1")
        server.enqueue(MockResponse.Builder().code(200).body("manual 2").build())
        val opened = manuals.file(game("2"))
        assertEquals("manual 2", opened.readText())
        assertTrue(!earlier.exists())
    }

    @Test
    fun `a fetch says how far along it is, from the length the server gives`() = runBlocking {
        val body = "x".repeat(200_000)
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
}
