package app.genkan.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import app.genkan.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Foreground service that keeps the queue running with the screen off and Genkan in the back. */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var queue: DownloadQueue

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runner: Job? = null
    /** The last start asked for; only read and written on the main thread. */
    private var latestStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        startForeground(NOTIFICATION_ID, notification("Preparing downloads…", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (runner?.isActive != true) {
            acquireLocks()
            runner = scope.launch {
                do {
                    queue.runQueue { row ->
                        val percent = if (row.bytesTotal > 0) (row.bytesDone * 100 / row.bytesTotal).toInt() else null
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, notification(row.name, percent))
                    }
                } while (!stopIfIdle())
            }
        }
        return START_STICKY
    }

    /**
     * Stops the service unless more work came in. A game queued just as the queue ran dry asks
     * for a start while this runner still looks busy, so that start launches nothing; stopping
     * anyway left it QUEUED until Genkan restarted. Here a start newer than the one this check
     * began with keeps the service (stopSelfResult refuses), and the runner goes round again.
     */
    private suspend fun stopIfIdle(): Boolean = withContext(Dispatchers.Main) {
        val startId = latestStartId
        if (queue.hasWork()) return@withContext false
        stopSelfResult(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "genkan:downloads").apply { acquire(LOCK_TIMEOUT_MS) }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "genkan:downloads").apply { acquire() }
    }

    private fun notification(text: String, percent: Int?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name) + " downloads")
            .setContentText(if (percent != null) "$text · $percent%" else text)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 1001
        /** Safety net only; the lock is released as soon as the queue empties. */
        private const val LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DownloadService::class.java))
        }
    }
}
