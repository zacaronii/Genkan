package app.genkan.library

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Full box setting: on for an install that never had it, and kept once switched. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class FullBoxSettingTest {
    @Test
    fun `on until switched off, and off once it is, then on again`() = runBlocking {
        val settings = LibraryViewSettings(ApplicationProvider.getApplicationContext())
        assertTrue(settings.fullBox.first())
        settings.setFullBox(false)
        assertFalse(settings.fullBox.first())
        // A new reader of the same store (the app started again) reads what was kept.
        assertFalse(LibraryViewSettings(ApplicationProvider.getApplicationContext()).fullBox.first())
        settings.setFullBox(true)
        assertTrue(settings.fullBox.first())
    }
}
