package app.genkan.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class PlayerSettingsTest {
    private val settings = PlayerSettings(ApplicationProvider.getApplicationContext())

    @Test
    fun `low-latency sound is on by default, and each console switches on its own`() = runBlocking {
        assertTrue(settings.lowLatency("psx"))
        settings.setLowLatency("psx", false)
        assertFalse(settings.lowLatency("psx"))
        assertTrue("another console keeps its own", settings.lowLatency("gba"))
        settings.setLowLatency("psx", true)
        assertTrue(settings.lowLatency("psx"))
        assertEquals(emptySet<String>(), settings.lowLatencyOff.first())
    }
}
