// Adapted from Argosy Launcher (GPL-3.0) @ 3480817: app/src/main/kotlin/com/nendo/argosy/hardware/FocusDirectorActivity.kt
package app.genkan.hardware

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity

private const val TAG = "FocusDirector"

/**
 * Invisible activity that pulls window-manager focus to a display, then finishes. On the Thor
 * controller input follows the last-touched screen (T3), so after a bottom-screen tap this is
 * launched on the top display to hand the controller back to the launcher or game.
 */
class FocusDirectorActivity : Activity() {

    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A new window may vote for another refresh rate, and the Thor's panel shows a mode
        // switch as a black band sweeping down the screen. Ask for the mode it is already in.
        display?.mode?.modeId?.let { mode -> window.attributes = window.attributes.apply { preferredDisplayModeId = mode } }
        // One pixel in the corner: enough to take focus, too small to draw anything visible.
        window.setLayout(1, 1)
        window.setGravity(Gravity.TOP or Gravity.START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        Log.d(TAG, "Launched on display ${display?.displayId}")
    }

    override fun onResume() {
        super.onResume()
        window.decorView.postDelayed({ finish() }, 50)
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) overridePendingTransition(0, 0)
    }

    companion object {
        fun launchOnDisplay(context: Context, displayId: Int) {
            val intent = Intent(context, FocusDirectorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            val options = ActivityOptions.makeCustomAnimation(context, 0, 0)
                .setLaunchDisplayId(displayId)
                .toBundle()
            Log.d(TAG, "Directing focus to display $displayId")
            context.startActivity(intent, options)
        }
    }
}
