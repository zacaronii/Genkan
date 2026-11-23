package app.genkan.core

import com.swordfish.libretrodroid.GLRetroView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerMessagesTest {
    @Test
    fun `a failed load names its reason`() {
        assertEquals("mGBA couldn't load the game file", PlayerMessages.loadError(GLRetroView.ERROR_LOAD_GAME, "mGBA"))
        assertEquals("mGBA couldn't be started", PlayerMessages.loadError(GLRetroView.ERROR_LOAD_LIBRARY, "mGBA"))
        assertEquals("PPSSPP needs graphics this device doesn't have", PlayerMessages.loadError(GLRetroView.ERROR_GL_NOT_COMPATIBLE, "PPSSPP"))
        assertEquals("mGBA stopped with an error", PlayerMessages.loadError(99, "mGBA"))
    }

    @Test
    fun `the media volume is off at zero or muted`() {
        assertTrue(PlayerMessages.volumeOff(0, muted = false))
        assertTrue(PlayerMessages.volumeOff(7, muted = true))
        assertFalse(PlayerMessages.volumeOff(7, muted = false))
    }
}
