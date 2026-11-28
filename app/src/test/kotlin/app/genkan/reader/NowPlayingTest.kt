package app.genkan.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTest {
    @Test
    fun `a game started as a test is one, and the next ordinary start isn't`() {
        val playing = NowPlaying()
        playing.started(7, "Made-up Quest", "snes", test = true)
        assertTrue(playing.game.value.test)
        playing.started(7, "Made-up Quest", "snes")
        assertFalse(playing.game.value.test)
        assertEquals("game:7", playing.game.value.key)
    }

    @Test
    fun `a file outside the library plays as a test under its own key, never a game's`() {
        val playing = NowPlaying()
        playing.started(7, "Made-up Quest", "snes")
        playing.startedTest("homebrew.nds", "nds")
        assertEquals("test:homebrew.nds", playing.game.value.key)
        assertNull(playing.game.value.rommRomId)
        assertTrue(playing.game.value.test)
    }

    @Test
    fun `a device round taking over makes what's playing a test play, the same game`() {
        val playing = NowPlaying()
        playing.started(7, "Made-up Quest", "snes")
        playing.markTest()
        assertEquals("game:7", playing.game.value.key)
        assertTrue(playing.game.value.test)
    }
}
