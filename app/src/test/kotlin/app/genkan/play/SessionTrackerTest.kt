package app.genkan.play

import android.app.Application
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.core.BuiltInPlayer
import app.genkan.core.CoreDef
import app.genkan.core.ICoreSession
import app.genkan.library.GameEntity
import app.genkan.romm.RomMSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** When a play ends: an emulator's when the launcher is back, the built-in player's when its game ends. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class SessionTrackerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dao = FakePlaySessionDao()
    private val player = BuiltInPlayer()
    private val romm = mockk<RomMSession> { every { api() } returns null }
    private val tracker = SessionTracker(context, dao, romm, player)

    /** The player's death watch on the running game, to end it as its process would. */
    private val death = slot<IBinder.DeathRecipient>()

    private fun startPlayer() {
        val binder = mockk<IBinder>(relaxed = true) { every { linkToDeath(capture(death), any()) } returns Unit }
        val session = mockk<ICoreSession>(relaxed = true) { every { asBinder() } returns binder }
        player.attach(session, romId = 7, core = mockk<CoreDef>(relaxed = true))
    }

    /** Waits for the tracker's own coroutine to see the player's change. */
    private fun settle() = Thread.sleep(300)

    @Test
    fun `an emulator's play ends when the launcher is back`() = runBlocking {
        tracker.begin(7, "org.ppsspp.ppsspp")
        assertTrue(tracker.onLauncherResumed())
        assertNull(dao.open())
    }

    @Test
    fun `the launcher in front for a moment doesn't end a built-in play`() = runBlocking {
        tracker.begin(7, context.packageName)
        startPlayer()
        settle()
        // The launcher resumes mid-game (on main this closed, or dropped, the play).
        assertFalse(tracker.onLauncherResumed())
        assertNotNull(dao.open())
    }

    @Test
    fun `a built-in play waits for the player's game to start`() = runBlocking {
        tracker.begin(7, context.packageName)
        // The launcher is back before the player's first frame.
        assertFalse(tracker.onLauncherResumed())
        assertNotNull(dao.open())
    }

    /** An activity event of Genkan's, as Android's usage stats record it. */
    private fun usage(at: Long, type: Int, activity: String) {
        val event = org.robolectric.shadows.ShadowUsageStatsManager.EventBuilder.buildEvent()
            .setPackage(context.packageName).setClass(activity).setTimeStamp(at).setEventType(type).build()
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.usage.UsageStatsManager::class.java)).addEvent(event)
    }

    @Test
    fun `a built-in play ends when its game ends, timed by the player's own activity`() = runBlocking {
        tracker.begin(7, context.packageName)
        dao.backdate(20_000)
        val start = dao.open()!!.startedAt
        val core = app.genkan.core.CoreActivity::class.java.name
        val home = "app.genkan.HomeActivity"
        val resumed = android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
        val paused = android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
        // The game from 1 s in; the launcher resumes for a moment at 10 s; the game goes on.
        usage(start + 1_000, resumed, core)
        usage(start + 10_000, resumed, home)
        usage(start + 10_100, paused, home)
        startPlayer()
        settle()
        assertFalse(tracker.onLauncherResumed())
        // The game ends 19 s in.
        usage(start + 19_000, paused, core)
        usage(start + 19_000, resumed, home)
        death.captured.binderDied()
        settle()
        assertNull(dao.open())
        val closed = dao.rows.single()
        assertEquals(7L, closed.romId)
        assertEquals(18_000L, closed.durationMs)
    }

    @Test
    fun `a built-in play under the 10 s floor is still dropped`() = runBlocking {
        tracker.begin(7, context.packageName)
        dao.backdate(8_000)
        val start = dao.open()!!.startedAt
        // The game showed for 5 s, then closed (a refused disc, say).
        usage(start + 1_000, android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED, app.genkan.core.CoreActivity::class.java.name)
        usage(start + 6_000, android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED, app.genkan.core.CoreActivity::class.java.name)
        startPlayer()
        settle()
        death.captured.binderDied()
        settle()
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a built-in play that never started ends once the launcher is back after a while`() = runBlocking {
        tracker.begin(7, context.packageName)
        dao.backdate(SessionTrackerTestLimits.OVERDUE_MS)
        assertTrue(tracker.onLauncherResumed())
        assertNull(dao.open())
    }
}

private object SessionTrackerTestLimits {
    /** Past SessionTracker's wait for the player's game to start. */
    const val OVERDUE_MS = 61_000L
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
    override suspend fun pending(limit: Int) = rows.filter { it.endedAt != null && !it.uploaded }.take(limit)
    override suspend fun markUploaded(ids: List<Long>) {
        rows.replaceAll { if (it.id in ids) it.copy(uploaded = true) else it }
    }
    override fun totals(romId: Long): Flow<PlayTotals> = flowOf(PlayTotals(0, 0, null))
    override fun recentlyPlayed(minMs: Long, limit: Int): Flow<List<GameEntity>> = flowOf(emptyList())
    override fun lastPlayedByGame(): Flow<List<GameLastPlayed>> = flowOf(emptyList())
    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }
}
