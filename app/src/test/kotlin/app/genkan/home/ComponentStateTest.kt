package app.genkan.home

import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentStateTest {

    @Test
    fun `default follows the manifest`() {
        assertFalse(isEnabledState(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, manifestDefault = false))
        assertTrue(isEnabledState(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, manifestDefault = true))
    }

    @Test
    fun `explicit states win over the manifest`() {
        assertTrue(isEnabledState(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, manifestDefault = false))
        assertFalse(isEnabledState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, manifestDefault = true))
        assertFalse(isEnabledState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, manifestDefault = true))
    }
}
