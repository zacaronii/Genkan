package app.genkan.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInDefaultTest {

    @Test
    fun `a fresh install starts with the built-in player on, an updated one keeps it off until switched on`() {
        // Never updated since it was installed: a fresh install.
        assertTrue(BuiltInDefault.isFreshInstall(firstInstallTime = 1_000L, lastUpdateTime = 1_000L))
        assertTrue(BuiltInDefault.on(freshInstall = true))
        // Updated since: an install from a version where the switch was off until switched on.
        assertFalse(BuiltInDefault.isFreshInstall(firstInstallTime = 1_000L, lastUpdateTime = 2_000L))
        assertFalse(BuiltInDefault.on(freshInstall = false))
    }
}
