// The default-display placement check is adapted from Argosy Launcher (GPL-3.0) @ 3480817:
// app/src/main/kotlin/com/nendo/argosy/hardware/SecondaryHomeActivity.kt
package app.genkan

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import app.genkan.capture.CaptureConsentActivity
import app.genkan.hardware.FocusDirectorActivity
import app.genkan.hardware.RefocusGate
import app.genkan.home.DualScreenState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.genkan.ui.GenkanTheme
import app.genkan.ui.CompanionMode
import app.genkan.ui.GameDetailsScreen
import app.genkan.ui.GameDetailsViewModel
import app.genkan.ui.PlayManualLayer
import app.genkan.ui.ReaderScreen
import app.genkan.ui.ReaderViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val TAG = "LowerActivity"

/**
 * Wait after the companion comes to the front before handing focus back: its window was just
 * added, and removing FocusDirector's within ~400 ms of that crashes the Thor (T5).
 */
private const val RESUME_REFOCUS_MS = 600L

/**
 * Bottom screen: the reading companion. Started by the OS through SECONDARY_HOME once Genkan
 * is the default home. Never finish() it to get rid of it; [DualScreenState.releaseLower]
 * disables the component instead (T1).
 */
@AndroidEntryPoint
class LowerActivity : ComponentActivity() {

    @Inject lateinit var dualScreen: DualScreenState

    private val reader: ReaderViewModel by viewModels()
    private val details: GameDetailsViewModel by viewModels()
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reader.onAudioPermission(granted)
    }

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
        // Controller input follows the focused screen (T3). A window that can't take focus
        // still gets touches, and touching it leaves focus where it was, so a tap on the bottom
        // screen no longer pulls the pad away from the top one. Handing focus back after each
        // tap launched a window over the game instead, and PPSSPP paused and redrew for it.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        setContent {
            GenkanTheme {
                val mode by details.mode.collectAsStateWithLifecycle()
                val canShowDetails by details.canShowDetails.collectAsStateWithLifecycle()
                when (mode) {
                    CompanionMode.DETAILS -> GameDetailsScreen(details, onShowReader = details::showReader)
                    CompanionMode.READER -> Box(Modifier.fillMaxSize()) {
                        ReaderScreen(
                            viewModel = reader,
                            onAskCaptureConsent = { startActivity(Intent(this@LowerActivity, CaptureConsentActivity::class.java)) },
                            onAskAudioPermission = { audioPermission.launch(Manifest.permission.RECORD_AUDIO) },
                            onShowGame = details::showDetails.takeIf { canShowDetails },
                        )
                        // The playing game's manual, over the reader.
                        PlayManualLayer(reader.manual)
                    }
                }
            }
        }
    }

    /**
     * Re-reported on every resume, not only in onCreate: when the screens sleep the bottom
     * display reads as off and [DualScreenState.refresh] forgets the companion, and the launcher
     * then restarts it on wake. That brings this same instance back, so onCreate never runs.
     */
    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        dualScreen.onLowerPlaced(ownDisplayId)
        // Coming to the front (Home, wake, a respawn) still moves the focused screen down here,
        // where no window can take the pad, so hand it back to the top one.
        handler.removeCallbacks(refocus)
        handler.postDelayed(refocus, maxOf(RESUME_REFOCUS_MS, refocusGate.delayFor(SystemClock.uptimeMillis())))
    }

    override fun onPause() {
        handler.removeCallbacks(refocus)
        super.onPause()
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
