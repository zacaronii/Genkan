package app.genkan.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherPresenceTest {

    @Test
    fun `the launcher is in front while it is started`() {
        val presence = LauncherPresence()
        val home = Any()
        assertFalse(presence.inFront.value)
        presence.started(home)
        assertTrue(presence.inFront.value)
        presence.stopped(home)
        assertFalse(presence.inFront.value)
    }

    @Test
    fun `a swiped-away launcher's late stop leaves the new one in front`() {
        // Android starts the new home before the old one's onStop, which comes after the new one's onResume.
        val presence = LauncherPresence()
        val old = Any()
        val new = Any()
        presence.started(old)
        presence.started(new)
        presence.stopped(old)
        assertTrue(presence.inFront.value)
        presence.stopped(new)
        assertFalse(presence.inFront.value)
    }
}
