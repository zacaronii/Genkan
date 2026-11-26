package app.genkan.core

import com.swordfish.libretrodroid.GLRetroView
import org.junit.Assert.assertEquals
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
    fun `a stream that won't open or stopped is said, not left silent`() {
        assertEquals("No sound: the sound stream wouldn't open (ErrorInternal)", PlayerMessages.noSound("the sound stream wouldn't open (ErrorInternal)"))
    }

    @Test
    fun `running warm says the next start is cooler but may be slower`() {
        assertEquals("Running warm: next start uses GL, cooler but it may run slower", PlayerMessages.runningWarm("GL"))
    }
}
