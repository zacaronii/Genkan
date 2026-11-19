package app.genkan.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Lets `scripts/recover-home.sh` hand Home back over adb (T14). Since Android 13 the shell may
 * not change another app's component state, so Genkan disables its own HOME alias and
 * companion when told to. Guarded in the manifest by android.permission.DUMP, which adb's
 * shell holds and ordinary apps cannot.
 */
class RecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RELEASE_HOME) return
        Log.w("RecoveryReceiver", "Releasing HOME and SECONDARY_HOME on request")
        SecondaryHomeComponent.setEnabled(context, false)
        HomeAppComponent.setEnabled(context, false)
        resultCode = 1
    }

    companion object {
        const val ACTION_RELEASE_HOME = "app.genkan.action.RELEASE_HOME"
    }
}
