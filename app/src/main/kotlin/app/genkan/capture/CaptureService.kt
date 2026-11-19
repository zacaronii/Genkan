package app.genkan.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Holds the screen capture in a foreground service, as Android requires for MediaProjection. */
@AndroidEntryPoint
class CaptureService : Service() {

    @Inject lateinit var capture: ScreenCapture

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            capture.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val data = intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        capture.start(intent.getIntExtra(EXTRA_RESULT_CODE, 0), data)
        // Consent lasts as long as this process: never restart with a stale one.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        capture.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Screen reading", NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Reading the game screen")
            .setContentText("Genkan reads the top screen's text for the reader")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "app.genkan.capture.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, CaptureService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
