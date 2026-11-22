package app.genkan.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LookupGameKeyTest {
    @Test
    fun `a manual opened from a game's details counts its lookups for that game`() {
        assertEquals("game:12", lookupGameKey(MANUAL_LINE, manualKey = "game:12", playingKey = "game:40"))
        assertEquals("game:12", lookupGameKey(MANUAL_LINE, manualKey = "game:12", playingKey = "none"))
    }

    @Test
    fun `every other lookup counts for the game being played, or for none`() {
        // The manual open during play: no key of its own.
        assertEquals("game:40", lookupGameKey(MANUAL_LINE, manualKey = null, playingKey = "game:40"))
        // A backlog line, while a details manual is open: still the game being played.
        assertEquals("game:40", lookupGameKey(7L, manualKey = "game:12", playingKey = "game:40"))
        assertNull(lookupGameKey(7L, manualKey = null, playingKey = "none"))
        assertNull(lookupGameKey(null, manualKey = null, playingKey = "none"))
    }
}
