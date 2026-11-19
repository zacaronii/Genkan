// Adapted from Argosy Launcher (GPL-3.0) @ 3480817: app/src/main/kotlin/com/nendo/argosy/hardware/FocusDirectorActivity.kt
package app.genkan.hardware

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

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
        Log.d(TAG, "Launched on display ${display?.displayId}")
    }

    override fun onResume() {
        super.onResume()
        window.decorView.postDelayed({ finish() }, 50)
    }

    companion object {
        fun launchOnDisplay(context: Context, displayId: Int) {
            val intent = Intent(context, FocusDirectorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            }
            val options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(displayId)
                .toBundle()
            Log.d(TAG, "Directing focus to display $displayId")
            context.startActivity(intent, options)
        }
    }
}
