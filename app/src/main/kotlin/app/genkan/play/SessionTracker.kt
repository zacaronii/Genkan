package app.genkan.play

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log
import app.genkan.core.CoreActivity
import app.genkan.romm.RomMPlaySessionEntry
import app.genkan.romm.RomMPlaySessionIngestPayload
import app.genkan.romm.RomMSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
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

    /**
     * A play of [romId] starts. A [test] play (a device round's, see TestMode) closes the one before
     * it but opens none: it's never timed, uploaded to RomM or followed by a save sync.
     */
    suspend fun begin(romId: Long, emulatorPackage: String, test: Boolean = false) {
        endOpenSession()
        if (test) {
            Log.i(TAG, "A test play of rom $romId: not recorded")
            return
        }
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

    /** A play that just ended: which game, in which emulator. */
    data class Ended(val romId: Long, val emulatorPackage: String)

    private val _ended = MutableSharedFlow<Ended>(extraBufferCapacity = 8)
    /** Each play that just ended, for the save sync and the bottom-screen check. */
    val ended: SharedFlow<Ended> = _ended.asSharedFlow()

    private suspend fun endOpenSession(): Boolean {
        val open = dao.open() ?: return false
        val now = System.currentTimeMillis()
        // The game is often in front before the session opens: look back for its RESUMED. The
        // built-in player shares Genkan's package, so only its own activity is the game.
        val classes = if (open.emulatorPackage == context.packageName) setOf(CoreActivity::class.java.name) else null
        val events = usageEvents(open.startedAt - LOOK_BACK_MS, now)
        val measured = PlayTime.measure(open.startedAt, now, open.emulatorPackage, events, classes)
        if (measured.durationMs < MIN_SESSION_MS) {
            // The emulator closed straight away (a missing BIOS, a refused file): not a play.
            dao.delete(open.id)
            Log.i(TAG, "Dropped a ${measured.durationMs} ms session of rom ${open.romId}")
        } else {
            dao.close(open.id, measured.endedAt, measured.durationMs)
            _ended.tryEmit(Ended(open.romId, open.emulatorPackage))
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
        /** How far before a session's start to read what was already in front (the launch takes seconds). */
        const val LOOK_BACK_MS = 60_000L
        /** RomM's ingest limit per request. */
        const val BATCH = 100
    }
}
