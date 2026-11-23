package app.genkan.download

import android.app.Application
import app.genkan.library.GameEntity
import app.genkan.romm.RomMAccount
import app.genkan.romm.RomMApiFactory
import app.genkan.romm.RomMSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The queue against a RomM that answers 500 (#18): a game whose details fail twice is marked
 * FAILED and the next game still downloads; a single 500 is asked again and the game installs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DownloadQueueFailureTest {
    private val server = MockWebServer()
    private val asked = mutableMapOf<String, Int>()

    @After
    fun stop() = server.close()

    private fun romJson(id: Long, file: String) =
        """{"id":$id,"platform_id":3,"platform_slug":"psp","name":"Game $id","fs_name":"$file","fs_size_bytes":3}"""

    private fun game(id: Long, file: String) = GameEntity(
        id = id, platformId = 3, platformSlug = "psp", name = "Game $id", sortTitle = "game $id",
        fsName = file, fsSizeBytes = 3, coverPath = null, hasMultipleFiles = false,
    )

    @Test
    fun `a game RomM keeps failing is marked failed and the next one still downloads`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val count = synchronized(asked) { (asked[path] ?: 0).plus(1).also { asked[path] = it } }
                return when {
                    // Game 1: 500 every time, so the retry gives up too.
                    path == "/api/roms/1" -> MockResponse.Builder().code(500).build()
                    // Game 2: one 500 (the token row collision #18 saw), then the details.
                    path == "/api/roms/2" -> if (count == 1) MockResponse.Builder().code(500).build() else MockResponse.Builder().body(romJson(2, "two.iso")).build()
                    path.startsWith("/api/roms/2/content/") -> MockResponse.Builder().body("abc").build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        // Signed in to the fake server. The stored account is encrypted with AndroidKeyStore,
        // which Robolectric lacks, so the session is stood in for; the API is the real one,
        // with its retry.
        val account = RomMAccount(server.url("/").toString(), "token", "device", 1, "tester", "4.0.0")
        val api = RomMApiFactory.create(account.baseUrl, account.token, OkHttpClient())
        val session = mockk<RomMSession> {
            every { currentAccount() } returns account
            every { api() } returns api
        }
        val dao = FakeDownloadDao()
        val queue = DownloadQueue(starter = {}, dao = dao, session = session, httpClient = OkHttpClient(), unpacked = app.genkan.core.UnpackedGames(java.io.File("build/unpacked-test")))

        queue.enqueue(game(1, "one.iso")).join()
        queue.enqueue(game(2, "two.iso")).join()
        queue.runQueue {}

        val first = dao.get(1)!!
        assertEquals(DownloadState.FAILED, first.state)
        assertTrue(first.error.orEmpty(), first.error.orEmpty().contains("500"))
        // Asked once and once more after the 500, then given up.
        assertEquals(2, asked["/api/roms/1"])
        val second = dao.get(2)!!
        assertEquals(DownloadState.INSTALLED, second.state)
        assertEquals(2, asked["/api/roms/2"])
        assertEquals("abc", File(second.path).readText())
        assertEquals(false, queue.hasWork())
    }
}
