package app.genkan.romm

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RomMRetryInterceptorTest {

    private val server = MockWebServer()
    private val waits = mutableListOf<Long>()
    private val client = OkHttpClient.Builder()
        .addInterceptor(RomMRetryInterceptor(sleep = { waits += it }))
        .build()

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun get() = client.newCall(Request.Builder().url(server.url("/api/roms/287")).build()).execute()

    @Test
    fun `a GET answered 500 is asked once more and succeeds`() {
        server.enqueue(MockResponse.Builder().code(500).body("Record has changed since last read").build())
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        get().use { response ->
            assertEquals(200, response.code)
            assertEquals("{}", response.body.string())
        }
        assertEquals(2, server.requestCount)
        assertEquals(listOf(RomMRetryInterceptor.RETRY_DELAY_MS), waits)
    }

    @Test
    fun `a GET answered 500 twice fails with the second answer`() {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(200).build())
        get().use { assertEquals(503, it.code) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `answers under 500 are passed on as they are`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        get().use { assertEquals(404, it.code) }
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun `a POST is never sent twice`() {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().code(200).build())
        val post = Request.Builder().url(server.url("/api/play-sessions")).post("{}".toByteArray().toRequestBody()).build()
        client.newCall(post).execute().use { assertEquals(500, it.code) }
        assertEquals(1, server.requestCount)
    }
}
