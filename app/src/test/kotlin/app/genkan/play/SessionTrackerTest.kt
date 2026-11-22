package app.genkan.play

import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.core.CoreActivity
import app.genkan.library.GameEntity
import app.genkan.romm.RomMSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsageStatsManager

/** How long a play lasts, from Android's usage events, once the launcher is back. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class SessionTrackerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dao = FakePlaySessionDao()
    private val romm = mockk<RomMSession> { every { api() } returns null }
    private val tracker = SessionTracker(context, dao, romm)

    private val core = CoreActivity::class.java.name
    private val home = "app.genkan.HomeActivity"

    private fun usage(pkg: String, activity: String, at: Long, type: Int) {
        val event = ShadowUsageStatsManager.EventBuilder.buildEvent()
            .setPackage(pkg).setClass(activity).setTimeStamp(at).setEventType(type).build()
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).addEvent(event)
    }

    /**
     * A play as the Thor showed it: Genkan opens the session once the launch is done, [ago] ms
     * back, with the game's activity already resumed 2 s before that; it leaves the front [played]
     * ms after the session opened, and Genkan's launcher comes back.
     */
    private fun play(pkg: String, activity: String, ago: Long, played: Long): Long = runBlocking {
        tracker.begin(7, pkg)
        dao.backdate(ago)
        val start = dao.open()!!.startedAt
        usage(pkg, activity, start - 2_000, UsageEvents.Event.ACTIVITY_RESUMED)
        usage(pkg, activity, start + played, UsageEvents.Event.ACTIVITY_PAUSED)
        usage(context.packageName, home, start + played, UsageEvents.Event.ACTIVITY_RESUMED)
        assertTrue(tracker.onLauncherResumed())
        start
    }

    @Test
    fun `a built-in play whose game was in front before the session opened is timed in full`() {
        // On main this play was "Dropped a 17 ms session": the game's one RESUMED came before the start.
        val start = play(context.packageName, core, ago = 80_000, played = 78_000)
        val row = dao.rows.single()
        assertEquals(78_000L, row.durationMs)
        assertEquals(start + 78_000, row.endedAt)
    }

    @Test
    fun `Genkan's launcher and bottom screen don't count as the built-in game`() {
        runBlocking {
            tracker.begin(7, context.packageName)
            dao.backdate(80_000)
            val start = dao.open()!!.startedAt
            // The bottom screen stays resumed throughout; the game plays 30 s.
            usage(context.packageName, "app.genkan.LowerActivity", start - 5_000, UsageEvents.Event.ACTIVITY_RESUMED)
            usage(context.packageName, core, start - 1_000, UsageEvents.Event.ACTIVITY_RESUMED)
            usage(context.packageName, core, start + 30_000, UsageEvents.Event.ACTIVITY_PAUSED)
            usage(context.packageName, home, start + 30_000, UsageEvents.Event.ACTIVITY_RESUMED)
            tracker.onLauncherResumed()
        }
        assertEquals(30_000L, dao.rows.single().durationMs)
    }

    @Test
    fun `an external emulator already in front at the start is timed in full too`() {
        play("org.example.emulator", "org.example.Game", ago = 80_000, played = 60_000)
        assertEquals(60_000L, dao.rows.single().durationMs)
    }

    @Test
    fun `an emulator that comes to the front after the start is timed as before`() {
        runBlocking {
            tracker.begin(7, "org.ppsspp.ppsspp")
            dao.backdate(80_000)
            val start = dao.open()!!.startedAt
            usage("org.ppsspp.ppsspp", "PpssppActivity", start + 1_000, UsageEvents.Event.ACTIVITY_RESUMED)
            usage("org.ppsspp.ppsspp", "PpssppActivity", start + 41_000, UsageEvents.Event.ACTIVITY_PAUSED)
            tracker.onLauncherResumed()
        }
        assertEquals(40_000L, dao.rows.single().durationMs)
    }

    @Test
    fun `a play under the 10 s floor is still dropped`() {
        play(context.packageName, core, ago = 80_000, played = 6_000)
        assertTrue(dao.rows.isEmpty())
        assertNull(runBlocking { dao.open() })
    }
}

/** Play sessions in memory, as Room keeps them. */
private class FakePlaySessionDao : PlaySessionDao {
    val rows = mutableListOf<PlaySessionEntity>()
    private var next = 1L

    /** Moves the open session's start [ms] into the past. */
    fun backdate(ms: Long) {
        val i = rows.indexOfFirst { it.endedAt == null }
        rows[i] = rows[i].copy(startedAt = rows[i].startedAt - ms)
    }

    override suspend fun insert(session: PlaySessionEntity): Long = next++.also { rows += session.copy(id = it) }
    override suspend fun open(): PlaySessionEntity? = rows.filter { it.endedAt == null }.maxByOrNull { it.startedAt }
    override suspend fun close(id: Long, endedAt: Long, durationMs: Long) {
        val i = rows.indexOfFirst { it.id == id }
        rows[i] = rows[i].copy(endedAt = endedAt, durationMs = durationMs)
    }
    override suspend fun pending(limit: Int) = rows.filter { it.endedAt != null && !it.uploaded && it.romId > 0 }.take(limit)
    override suspend fun markUploaded(ids: List<Long>) {
        rows.replaceAll { if (it.id in ids) it.copy(uploaded = true) else it }
    }
    override fun totals(romId: Long): Flow<PlayTotals> = flowOf(PlayTotals(0, 0, null))
    override fun recentlyPlayed(minMs: Long, limit: Int): Flow<List<GameEntity>> = flowOf(emptyList())
    override fun lastPlayedByGame(): Flow<List<GameLastPlayed>> = flowOf(emptyList())
    override fun recentPlays(minMs: Long): Flow<List<GameLastPlayed>> = flowOf(emptyList())
    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }
}
