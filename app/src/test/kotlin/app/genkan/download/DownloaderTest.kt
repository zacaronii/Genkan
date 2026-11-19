package app.genkan.download

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DownloaderTest {

    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val downloader = Downloader(OkHttpClient())
    private val payload = "0123456789".repeat(100) // 1000 bytes

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun target() = File(tmp.root, "ps1/Game (USA).chd")
    private fun part() = File(tmp.root, "ps1/Game (USA).chd.part")

    @Test
    fun `a fresh download lands under its real name with the token sent`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(payload).build())
        downloader.download(server.url("/f").toString(), "rmm_x", target(), 1000) {}
        assertEquals(payload, target().readText())
        assertFalse(part().exists())
        val request = server.takeRequest()
        assertEquals("Bearer rmm_x", request.headers["Authorization"])
        assertNull(request.headers["Range"])
    }

    @Test
    fun `an interrupted download resumes from the part file`() = runBlocking {
        part().parentFile!!.mkdirs()
        part().writeText(payload.take(400))
        server.enqueue(MockResponse.Builder().code(206).body(payload.drop(400)).build())
        val reported = mutableListOf<Long>()
        downloader.download(server.url("/f").toString(), null, target(), 1000) { reported += it }
        assertEquals("bytes=400-", server.takeRequest().headers["Range"])
        assertEquals(payload, target().readText())
        assertEquals(400L, reported.first())
        assertEquals(1000L, reported.last())
    }

    @Test
    fun `a server that ignores the range restarts the file from zero`() = runBlocking {
        part().parentFile!!.mkdirs()
        part().writeText("garbage!")
        server.enqueue(MockResponse.Builder().code(200).body(payload).build())
        downloader.download(server.url("/f").toString(), null, target(), 1000) {}
        assertEquals(payload, target().readText())
    }

    @Test
    fun `a short body fails and keeps the part file for the next try`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(payload.take(600)).build())
        val failure = runCatching { downloader.download(server.url("/f").toString(), null, target(), 1000) {} }
        assertTrue(failure.exceptionOrNull() is IOException)
        assertFalse(target().exists())
        assertEquals(600L, part().length())
    }

    @Test
    fun `http errors fail without touching the target`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).body("no").build())
        val failure = runCatching { downloader.download(server.url("/f").toString(), null, target(), 1000) {} }
        assertTrue(failure.exceptionOrNull()?.message!!.contains("403"))
        assertFalse(target().exists())
    }

    @Test
    fun `a complete file is not downloaded again`() = runBlocking {
        target().parentFile!!.mkdirs()
        target().writeText(payload)
        downloader.download(server.url("/f").toString(), null, target(), 1000) {}
        assertEquals(0, server.requestCount)
    }
}
