package app.genkan.art

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Art on the device, against a fake server with made-up images. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36])
class ArtCacheTest {
    @get:Rule val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private var clock = 1_000_000_000_000L
    /** Paths answered with an HTML page (a proxy's sign-in page), with no validator, or with a slow body. */
    private val html = mutableSetOf<String>()
    private val noValidator = mutableSetOf<String>()
    private val slowBody = mutableSetOf<String>()
    private val broken = mutableSetOf<String>()

    /** What the server holds: path to (image width, ETag). */
    private val files = Collections.synchronizedMap(mutableMapOf("/assets/romm/resources/roms/1/cover.png" to (10 to "v1")))
    private val slow = mutableSetOf<String>()

    private fun png(width: Int): Buffer {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        return Buffer().write(out.toByteArray())
    }

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (path in html) return MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html>Sign in</html>").build()
                val (width, etag) = files[path] ?: return MockResponse.Builder().code(404).build()
                if (request.headers["If-None-Match"] == etag) return MockResponse.Builder().code(304).addHeader("ETag", etag).build()
                val body = if (path in broken) Buffer().writeUtf8("not a picture") else png(width)
                return MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").body(body)
                    .apply { if (path !in noValidator) addHeader("ETag", etag) }
                    .apply { if (path in slow) headersDelay(1, TimeUnit.SECONDS) }
                    .apply { if (path in slowBody) bodyDelay(1, TimeUnit.SECONDS) }
                    .build()
            }
        }
        server.start()
    }

    @After fun stop() = server.close()

    private var baseUrl: String? = null
    private var signedOut = false
    private val disk by lazy { DiskCache.Builder().directory(temp.newFolder("art").toOkioPath()).maxSizeBytes(50L shl 20).build() }
    private val strategy = ArtCacheStrategy(now = { clock })

    private fun loader(client: OkHttpClient = OkHttpClient(), memory: Boolean = false): ImageLoader =
        ArtCache.loader(context, disk, { client }, { if (signedOut) null else baseUrl ?: server.url("/").toString() }, strategy, memory)

    private fun request(path: String, check: Boolean = false) = ImageRequest.Builder(context).data(RomMArt(path))
        .apply { if (check) extras[ArtCacheStrategy.REVALIDATE] = true }
        .build()

    private fun ImageLoader.width(path: String, check: Boolean = false): Int = runBlocking {
        val result = execute(request(path, check))
        assertTrue("loaded $path: $result", result is SuccessResult)
        (result as SuccessResult).image.width
    }

    private val cover = "/assets/romm/resources/roms/1/cover.png"

    @Test
    fun `art seen once is drawn from disk with no request, after a sync's new ts and a new server address`() {
        val loader = loader()
        assertEquals(10, loader.width("$cover?ts=20261120010000"))
        assertEquals(1, server.requestCount)
        // A sync: the game changed on the server, so its ts did, not its art.
        assertEquals(10, loader.width("$cover?ts=20261123050000"))
        // Signed in by another address: nothing listens there, and nothing needs to.
        baseUrl = "http://127.0.0.1:9/"
        assertEquals(10, loader.width("$cover?ts=20261123050000"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the daily check asks with the ETag, a 304 keeps the copy, and it isn't asked again within a day`() {
        val loader = loader()
        loader.width(cover)
        // The screen's own loads never ask, however old the copy.
        clock += 3 * ArtCacheStrategy.CHECK_EVERY_MS
        loader.width(cover)
        assertEquals(1, server.requestCount)
        // The check does, once it's due.
        loader.width(cover, check = true)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals("v1", server.takeRequest().headers["If-None-Match"])
        clock += ArtCacheStrategy.CHECK_EVERY_MS / 2
        loader.width(cover, check = true)
        assertEquals("not again within a day", 2, server.requestCount)
        assertTrue(!strategy.takeReplaced(RomMArt(cover).key))
    }

    @Test
    fun `art replaced on the server is fetched again by the check, reported once done, and then drawn new`() {
        val loader = loader()
        assertEquals(10, loader.width(cover))
        files[cover] = 20 to "v2"
        clock += ArtCacheStrategy.CHECK_EVERY_MS + 1
        loader.width(cover, check = true)
        assertTrue(strategy.takeReplaced(RomMArt(cover).key))
        assertTrue("reported once", !strategy.takeReplaced(RomMArt(cover).key))
        assertEquals(20, loader.width(cover))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `with a slow new file, the screen's load after the check has settled draws the new picture`() {
        // The screen's loader keeps bitmaps in memory, as the app's does.
        val screen = loader(memory = true)
        assertEquals(10, screen.width(cover))
        files[cover] = 20 to "v2"
        slowBody += cover
        clock += ArtCacheStrategy.CHECK_EVERY_MS + 1
        // The background check, then what ArtLoaders.settle does once it has returned.
        loader().width(cover, check = true)
        assertTrue(strategy.takeReplaced(RomMArt(cover).key))
        screen.memoryCache?.keys?.filter { it.key == RomMArt(cover).key }?.forEach { screen.memoryCache?.remove(it) }
        assertEquals(20, screen.width(cover))
    }

    @Test
    fun `a page that isn't an image is never kept, and the next load asks again`() {
        html += cover
        val loader = loader()
        runBlocking { loader.execute(request(cover)) }
        runBlocking { loader.execute(request(cover)) }
        assertEquals(2, server.requestCount)
        // The server answers properly again: the art is fetched and kept.
        html -= cover
        assertEquals(10, loader.width(cover))
        assertEquals(10, loader.width(cover))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a copy with no validator is fetched again, plainly, when its check is due`() {
        noValidator += cover
        val loader = loader()
        loader.width(cover)
        clock += ArtCacheStrategy.CHECK_EVERY_MS + 1
        loader.width(cover, check = true)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals(null, server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun `signed out, a load fails but the copy on disk stays`() {
        val loader = loader()
        assertEquals(10, loader.width(cover))
        // No account: no URL can be made. The load isn't drawn from disk either (the fetcher is
        // where a RomM file is found), but the copy is kept for when the account is back.
        signedOut = true
        assertTrue(runBlocking { loader.execute(request(cover)) } is coil3.request.ErrorResult)
        signedOut = false
        assertEquals(10, loader.width(cover))
        assertEquals("still on disk: no request", 1, server.requestCount)
    }

    @Test
    fun `a file on disk that won't decode is dropped, so the next load asks the server`() {
        broken += cover
        val loader = loader()
        assertTrue(runBlocking { loader.execute(request(cover)) } is coil3.request.ErrorResult)
        broken -= cover
        assertEquals(10, loader.width(cover))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `fetching ahead leaves the screen's request first in line`() = runBlocking {
        val shelf = (1..6).map { "/assets/romm/resources/roms/$it/spine.png" }
        shelf.forEach { files[it] = 8 to "s$it"; slow += it }
        // Two loaders on their own dispatchers, as ArtLoaders makes them: the background one takes two at a time.
        val background = loader(OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 }).build())
        val screen = loader(OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher()).build())
        val done = Collections.synchronizedList(mutableListOf<String>())
        val ahead = shelf.map { path -> async(kotlinx.coroutines.Dispatchers.IO) { background.execute(request(path)); done += path } }
        Thread.sleep(100)
        screen.execute(request(cover))
        done += "screen"
        ahead.forEach { it.await() }
        assertEquals("the screen's cover came before any of the shelf fetched ahead", "screen", done.first())
    }

    @Test
    fun `the old cache folder is removed once, by its own path`() {
        val old = ArtCache.oldDirectory(context).apply { mkdirs(); resolve("journal").writeText("x") }
        val other = java.io.File(context.cacheDir, "manuals").apply { mkdirs(); resolve("keep.pdf").writeText("x") }
        assertTrue(ArtCache.removeOldDirectory(context))
        assertTrue(!old.exists())
        assertTrue("nothing else in the cache folder", other.resolve("keep.pdf").exists())
        assertTrue(!ArtCache.removeOldDirectory(context))
    }
}
