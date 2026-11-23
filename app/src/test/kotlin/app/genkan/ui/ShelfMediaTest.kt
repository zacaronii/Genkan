package app.genkan.ui

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The focused game's disc or cartridge: in its case when the focus arrives, then sliding out. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ShelfMediaTest {
    // The host activity createComposeRule starts, registered here instead of through ui-test-manifest.
    @get:Rule(order = 0)
    val activity = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
            org.robolectric.Shadows.shadowOf(context.packageManager)
                .addActivityIfNotPresent(android.content.ComponentName(context, androidx.activity.ComponentActivity::class.java))
        }
    }

    /**
     * Effects are queued, not run while a composition is applied: as on the device, where an effect
     * launched by a frame's composition runs only after that frame is drawn.
     */
    private val effects = StandardTestDispatcher()

    @get:Rule(order = 1)
    val compose = createComposeRule(effects)

    /** One frame: what's due by then runs, then the frame itself. */
    private fun frame() {
        effects.scheduler.advanceTimeBy(16)
        effects.scheduler.runCurrent()
        compose.mainClock.advanceTimeByFrame()
        effects.scheduler.runCurrent()
    }

    @Test
    fun `a newly focused game's disc is in its case in the frame the focus arrives`() {
        var focused by mutableIntStateOf(0)
        // What each composition hands the disc to draw, as it's applied (before any effect runs).
        val drawn = mutableListOf<Pair<Int, Float>>()
        val accessors = mutableSetOf<() -> Float>()
        var slide: androidx.compose.animation.core.Animatable<Float, *>? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val now = focused
            val media = rememberMediaSlide(shelfId = 1L, focused = now, active = true, staticMedia = null)
            slide = media
            // Read the way a case reads it: through the accessor the shelf gives every case.
            val out = rememberMediaOut(media)
            SideEffect {
                drawn += now to out()
                accessors += out
            }
        }
        // The first game's disc rests, then slides all the way out.
        repeat(((ShelfDefaults.MEDIA_REST_MS + ShelfDefaults.MEDIA_SLIDE_MS + 300L) / 16).toInt()) { frame() }
        assertEquals("the first disc is out", 1f, slide!!.value, 0.001f)

        focused = 1
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(3) { frame() }
        assertEquals("the second disc in the frame the focus arrives", 0f, drawn.first { it.first == 1 }.second, 0.001f)
        // One accessor throughout, so the step gave no case a changed parameter.
        assertEquals("accessors given to the cases", 1, accessors.size)
    }
}
