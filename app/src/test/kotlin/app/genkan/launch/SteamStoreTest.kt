package app.genkan.launch

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    /** An `appdetails` answer for app 4242, in the shape the store sends; every value is made up. */
    private fun answer(
        name: String? = "時計塔の弟子",
        summary: String = "止まった時計を&quot;もう一度&quot;動かす物語。<br>全七章。",
        genres: String = """[{"id":"25","description":"アドベンチャー"},{"id":"23","description":"インディー"}]""",
        date: String = "2019年3月14日",
    ) = """
        {"4242":{"success":true,"data":{
          "type":"game","steam_appid":4242,
          ${name?.let { "\"name\":\"$it\"," } ?: ""}
          "short_description":"$summary",
          "genres":$genres,
          "developers":["Example Soft"],"publishers":["Sample Publishing","Example Soft"],
          "release_date":{"coming_soon":false,"date":"$date"},
          "background_raw":"https://example.invalid/page_bg_raw.jpg",
          "background":"https://example.invalid/page_bg.jpg",
          "screenshots":[{"id":0,"path_thumbnail":"https://example.invalid/ss0.600x338.jpg","path_full":"https://example.invalid/ss0.1920x1080.jpg"},
                         {"id":1,"path_thumbnail":"https://example.invalid/ss1.600x338.jpg","path_full":"https://example.invalid/ss1.1920x1080.jpg"}]
        }}}
    """.trimIndent()

    private fun body(json: String) = MockResponse.Builder().code(200).body(json).build()

    private fun store() = SteamStore(folder.root, OkHttpClient(), server.url("/"))

    @Test
    fun `an answer becomes the details, its HTML made plain`() {
        val details = SteamStore.parse(answer(), 4242)!!
        assertEquals("時計塔の弟子", details.name)
        assertEquals("止まった時計を\"もう一度\"動かす物語。\n全七章。", details.summary)
        assertEquals(listOf("アドベンチャー", "インディー"), details.genres)
        assertEquals(2019, details.releaseYear)
        assertEquals(listOf("Example Soft"), details.developers)
        assertEquals(listOf("Sample Publishing", "Example Soft"), details.publishers)
        assertEquals("https://example.invalid/page_bg_raw.jpg", details.background)
        assertEquals(listOf("https://example.invalid/ss0.1920x1080.jpg", "https://example.invalid/ss1.1920x1080.jpg"), details.screenshots)
    }

    @Test
    fun `the year is found in either language's date`() {
        assertEquals(2020, SteamStore.parse(answer(date = "9 Dec, 2020"), 4242)!!.releaseYear)
        assertNull(SteamStore.parse(answer(date = "近日登場"), 4242)!!.releaseYear)
    }

    @Test
    fun `an app the store doesn't know, another app's answer, or not JSON, is nothing`() {
        assertNull(SteamStore.parse("""{"4242":{"success":false}}""", 4242))
        assertNull(SteamStore.parse(answer(), 7))
        assertNull(SteamStore.parse("<html>", 4242))
    }

    @Test
    fun `the details are asked for in Japanese, kept, and not asked for again`() = runTest {
        server.enqueue(body(answer()))
        assertEquals("時計塔の弟子", store().details(4242)!!.name)
        val request = server.takeRequest()
        assertEquals("/api/appdetails", request.url.encodedPath)
        assertEquals("4242", request.url.queryParameter("appids"))
        assertEquals("japanese", request.url.queryParameter("l"))
        // Nothing else is asked for: no country, and no key.
        assertEquals(setOf("appids", "l"), request.url.queryParameterNames)
        // A new run reads the kept answer.
        assertEquals("時計塔の弟子", store().details(4242)!!.name)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the default language fills only what the Japanese leaves empty`() = runTest {
        server.enqueue(body(answer(summary = "", genres = "[]")))
        server.enqueue(body(answer(name = "The Clock Tower's Apprentice", summary = "A made-up summary.", genres = """[{"id":"25","description":"Adventure"}]""")))
        val details = store().details(4242)!!
        assertEquals("時計塔の弟子", details.name)
        assertEquals("A made-up summary.", details.summary)
        assertEquals(listOf("Adventure"), details.genres)
        server.takeRequest()
        assertNull(server.takeRequest().url.queryParameter("l"))
    }

    @Test
    fun `Japanese with nothing missing doesn't ask for the default`() = runTest {
        server.enqueue(body(answer()))
        store().details(4242)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a failed fetch is neither kept nor remembered, so the next focus asks again`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(body(answer()))
        val store = store()
        assertNull(store.details(4242))
        assertEquals("時計塔の弟子", store.details(4242)!!.name)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an app the store doesn't know isn't kept, and isn't asked for again this run`() = runTest {
        server.enqueue(body("""{"4242":{"success":false}}"""))
        val store = store()
        assertNull(store.details(4242))
        assertNull(store.details(4242))
        assertEquals(1, server.requestCount)
        assertFalse(folder.root.walk().any { it.isFile })
    }

    @Test
    fun `the store's details go into the game's columns`() {
        val game = app.genkan.library.GameEntity(-1_004_242, LocalShelves.PC_ID, LocalShelves.PC_SLUG, "Clock Tower", "clock tower", "4242", 0, null, false)
        val shown = SteamStore.parse(answer(), 4242)!!.applyTo(game)
        assertEquals("時計塔の弟子", shown.name)
        assertEquals("アドベンチャー · インディー", shown.genres)
        assertEquals(2019, shown.releaseYear)
        assertEquals("Example Soft", shown.developer)
        assertEquals("Sample Publishing\nExample Soft", shown.publishers)
        assertTrue(shown.summary!!.startsWith("止まった時計"))
        assertEquals(game.id, shown.id)
    }

    @Test
    fun `a 200 that isn't an answer for the app is a failed fetch, asked again on the next focus`() = runTest {
        // What the store can send while it's limiting requests, then an error page, then the answer.
        server.enqueue(body("null"))
        server.enqueue(body("<html>Error</html>"))
        server.enqueue(body(answer()))
        val store = store()
        assertNull(store.details(4242))
        assertNull(store.details(4242))
        assertEquals("時計塔の弟子", store.details(4242)!!.name)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `only the store's success false says the app is unknown`() {
        assertTrue(SteamStore.unknownApp("""{"4242":{"success":false}}""", 4242))
        assertFalse(SteamStore.unknownApp("null", 4242))
        assertFalse(SteamStore.unknownApp("<html>", 4242))
        assertFalse(SteamStore.unknownApp("""{"7":{"success":false}}""", 4242))
        assertFalse(SteamStore.unknownApp(answer(), 4242))
    }
}
