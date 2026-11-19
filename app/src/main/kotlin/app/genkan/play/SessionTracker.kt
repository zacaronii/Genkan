package app.genkan.play

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log
import app.genkan.romm.RomMPlaySessionEntry
import app.genkan.romm.RomMPlaySessionIngestPayload
import app.genkan.romm.RomMSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks plays of RomM games and reports them to RomM. A session opens when Genkan launches a
 * game and closes the next time the launcher comes back to the front, which is how Genkan learns
 * the player left the game.
 */
@Singleton
class SessionTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PlaySessionDao,
    private val session: RomMSession,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLock = Mutex()

    suspend fun begin(romId: Long, emulatorPackage: String) {
        endOpenSession()
        dao.insert(PlaySessionEntity(romId = romId, emulatorPackage = emulatorPackage, startedAt = System.currentTimeMillis()))
    }

    /** Closes the running session, if any, and starts its upload. True when one was closed. */
    suspend fun onLauncherResumed(): Boolean {
        val closed = endOpenSession()
        if (closed) scope.launch { upload() }
        return closed
    }

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    private suspend fun endOpenSession(): Boolean {
        val open = dao.open() ?: return false
        val now = System.currentTimeMillis()
        val measured = PlayTime.measure(open.startedAt, now, open.emulatorPackage, usageEvents(open.startedAt, now))
        if (measured.durationMs < MIN_SESSION_MS) {
            // The emulator closed straight away (a missing BIOS, a refused file): not a play.
            dao.delete(open.id)
            Log.i(TAG, "Dropped a ${measured.durationMs} ms session of rom ${open.romId}")
        } else {
            dao.close(open.id, measured.endedAt, measured.durationMs)
            Log.i(TAG, "Session of rom ${open.romId} in ${open.emulatorPackage}: ${measured.durationMs / 1000} s")
        }
        return true
    }

    private fun usageEvents(from: Long, to: Long): List<UsageSample>? {
        if (!hasUsageAccess()) return null
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val events = usage.queryEvents(from, to)
        val event = UsageEvents.Event()
        val samples = ArrayList<UsageSample>()
        while (events.getNextEvent(event)) {
            val kind = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageKind.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageKind.PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> UsageKind.STOPPED
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageKind.SCREEN_OFF
                else -> null
            } ?: continue
            samples += UsageSample(event.timeStamp, kind, event.packageName, event.className)
        }
        return samples
    }

    /** Sends finished sessions to RomM in batches; the server de-duplicates resends. */
    suspend fun upload() = uploadLock.withLock {
        val api = session.api() ?: return@withLock
        val deviceId = session.deviceId() ?: return@withLock
        while (true) {
            val batch = dao.pending(BATCH)
            if (batch.isEmpty()) return@withLock
            val payload = RomMPlaySessionIngestPayload(
                deviceId = deviceId,
                sessions = batch.map {
                    RomMPlaySessionEntry(
                        romId = it.romId,
                        startTime = Instant.ofEpochMilli(it.startedAt).toString(),
                        endTime = Instant.ofEpochMilli(it.endedAt!!).toString(),
                        durationMs = it.durationMs!!,
                    )
                },
            )
            val response = try {
                api.ingestPlaySessions(payload)
            } catch (e: Exception) {
                Log.w(TAG, "Play-session upload failed: $e"); return@withLock
            }
            if (response.code() == 401) {
                session.onUnauthorized(); return@withLock
            }
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                Log.w(TAG, "Play-session upload refused (${response.code()})"); return@withLock
            }
            // "error" is the server rejecting that entry (an unknown rom, say); resending won't help.
            body.results.filter { it.status == "error" }.forEach { Log.w(TAG, "RomM rejected a session: ${it.detail}") }
            dao.markUploaded(batch.map { it.id })
            Log.i(TAG, "Uploaded ${body.createdCount} play sessions (${body.skippedCount} already known)")
        }
    }

    private companion object {
        const val TAG = "SessionTracker"
        const val MIN_SESSION_MS = 10_000L
        /** RomM's ingest limit per request. */
        const val BATCH = 100
    }
}
