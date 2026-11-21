package app.genkan.core

import app.genkan.home.ScreenMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayDefaultTest {
    @Test
    fun `on with one screen, whatever the system`() {
        assertTrue(OverlayDefault.on(ScreenMode.SINGLE, "psp"))
        assertTrue(OverlayDefault.on(ScreenMode.SINGLE, null))
    }

    @Test
    fun `with two screens, on only for systems that use both`() {
        assertTrue(OverlayDefault.on(ScreenMode.DUAL, "nds"))
        assertTrue(OverlayDefault.on(ScreenMode.DUAL, "3ds"))
        assertFalse(OverlayDefault.on(ScreenMode.DUAL, "psp"))
        assertFalse(OverlayDefault.on(ScreenMode.DUAL, "psx"))
        assertFalse(OverlayDefault.on(ScreenMode.DUAL, null))
    }
}
