// The default-display placement check is adapted from Argosy Launcher (GPL-3.0) @ 3480817:
// app/src/main/kotlin/com/nendo/argosy/hardware/SecondaryHomeActivity.kt
package app.genkan

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.genkan.hardware.FocusDirectorActivity
import app.genkan.hardware.RefocusGate
import app.genkan.home.DualScreenState
import app.genkan.ui.GenkanTheme
import app.genkan.ui.ReaderScreen
import app.genkan.ui.ReaderViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val TAG = "LowerActivity"

/** Debounce after a touch ends before focus is handed back to the top screen. */
private const val REFOCUS_DEBOUNCE_MS = 120L

/**
 * Bottom screen: the reading companion. Started by the OS through SECONDARY_HOME once Genkan
 * is the default home. Never finish() it to get rid of it; [DualScreenState.releaseLower]
 * disables the component instead (T1).
 */
@AndroidEntryPoint
class LowerActivity : ComponentActivity() {

    @Inject lateinit var dualScreen: DualScreenState

    private val reader: ReaderViewModel by viewModels()

    private val handler = Handler(Looper.getMainLooper())
    private val refocusGate = RefocusGate()
    private val refocus = Runnable {
        refocusGate.markLaunched(SystemClock.uptimeMillis())
        FocusDirectorActivity.launchOnDisplay(this, Display.DEFAULT_DISPLAY)
    }

    private val ownDisplayId: Int get() = display.displayId

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (releaseIfOnDefaultDisplay()) return
        dualScreen.onLowerPlaced(ownDisplayId)

        setContent {
            GenkanTheme {
                ReaderScreen(viewModel = reader)
            }
        }
    }

    /**
     * Controller input follows the last-touched screen (T3). After every touch on the bottom
     * screen, send focus back to the top display so the pad keeps driving the launcher or game.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            handler.removeCallbacks(refocus)
            val wait = maxOf(REFOCUS_DEBOUNCE_MS, refocusGate.delayFor(SystemClock.uptimeMillis()))
            handler.postDelayed(refocus, wait)
        }
        return handled
    }

    override fun onDestroy() {
        handler.removeCallbacks(refocus)
        if (!isChangingConfigurations) dualScreen.onLowerGone(ownDisplayId)
        super.onDestroy()
    }

    /**
     * The companion is only ever placed on the secondary display, so landing on the default one
     * means the OS refused that placement (T2). Left running it would cover the launcher and be
     * relaunched in a loop, so release the component and drop to single-screen instead.
     */
    private fun releaseIfOnDefaultDisplay(): Boolean {
        if (ownDisplayId != Display.DEFAULT_DISPLAY) return false
        Log.w(TAG, "Companion placed on the default display; falling back to single-screen")
        dualScreen.onLowerPlaced(Display.DEFAULT_DISPLAY)
        dualScreen.releaseLower()
        finish()
        return true
    }
}
