package app.genkan.library

import android.content.Context
import app.genkan.romm.RomMSession
import coil3.request.Disposable
import coil3.request.ImageResult
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the warmer fetches and keeps, with the network replaced by a list of fake requests. */
class ArtworkWarmerFetchTest {

    private class FakeRequest(val url: String, val onDone: () -> Unit) : Disposable {
        override val job: Deferred<ImageResult> = CompletableDeferred()
        override var isDisposed = false
        override fun dispose() {
            isDisposed = true
        }
    }

    private var signedIn = true
    private val requests = mutableListOf<FakeRequest>()
    private val warmer = ArtworkWarmer(mockk<Context>(relaxed = true), mockk<RomMSession>(relaxed = true)).apply {
        baseUrl = { if (signedIn) "https://romm.example.com" else null }
        fetch = { url, onDone -> FakeRequest(url, onDone).also { requests += it } }
    }

    private val games = (0L until 40L).map { id ->
        GameEntity(
            id = id, platformId = 1, platformSlug = "psx", name = "Game $id", sortTitle = "game $id",
            fsName = null, fsSizeBytes = 0, coverPath = "/c/$id.png", hasMultipleFiles = false,
        )
    }

    @Test
    fun `signed out fetches nothing, and the same focus warms once signed in`() {
        signedIn = false
        warmer.warmAround(games, 10)
        assertTrue(requests.isEmpty())
        signedIn = true
        warmer.warmAround(games, 10)
        assertEquals(ArtworkWarmer.warmPaths(games, 10, 0).size, requests.size)
        assertEquals("/c/10.png", requests.first().url)
    }

    @Test
    fun `signing out mid-fetch drops what was running, and it is fetched again later`() {
        warmer.warmAround(games, 10)
        val first = requests.toList()
        signedIn = false
        warmer.warmAround(games, 10)
        assertTrue(first.all { it.isDisposed })
        signedIn = true
        warmer.warmAround(games, 10)
        assertEquals(first.size * 2, requests.size)
    }

    @Test
    fun `asking again for the same focus starts nothing new`() {
        warmer.warmAround(games, 10)
        val count = requests.size
        warmer.warmAround(games, 10)
        assertEquals(count, requests.size)
    }

    @Test
    fun `a step keeps fetches still wanted and drops the rest`() {
        warmer.warmAround(games, 10, moving = true)
        warmer.warmAround(games, 11, moving = true)
        val ahead = requests.first { it.url.endsWith("/c/15.png") }
        warmer.warmAround(games, 12, moving = true)
        assertFalse(ahead.isDisposed)
        assertEquals(1, requests.count { it.url.endsWith("/c/15.png") })
        // Game 3 was in the first, resting set and is too far behind a focus moving right.
        assertTrue(requests.first { it.url.endsWith("/c/3.png") }.isDisposed)
    }

    @Test
    fun `art already on disk isn't fetched again`() {
        warmer.warmAround(games, 10)
        requests.forEach { it.onDone() }
        val count = requests.size
        warmer.warmAround(games, 11)
        // Only game 18 is new to the window; everything else finished.
        assertEquals(listOf("/c/18.png"), requests.drop(count).map { it.url })
    }

    @Test
    fun `a game's art at a new ts is asked for again (the image cache answers it from disk, by its path)`() {
        val v1 = games.map { it.copy(coverPath = "${it.coverPath}?ts=1") }
        warmer.warmAround(v1, 10)
        requests.forEach { it.onDone() }
        val count = requests.size
        // Game 10 changed on the server: its path is new, the rest are the same.
        val v2 = v1.map { if (it.id == 10L) it.copy(coverPath = "/c/10.png?ts=2") else it }
        warmer.warmAround(v2, 10)
        assertEquals(listOf("/c/10.png?ts=2"), requests.drop(count).map { it.url })
    }
}
