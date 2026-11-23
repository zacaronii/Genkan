package app.genkan.download

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import app.genkan.library.GameEntity
import app.genkan.romm.RomMAccountStore
import app.genkan.romm.RomMSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The queue when Android refuses to start its service, as it does while Genkan is in the background. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DownloadQueueTest {

    private val dao = FakeDownloadDao()
    private var refuse = true
    private var starts = 0
    private val queue = DownloadQueue(
        starter = {
            starts++
            if (refuse) throw ForegroundServiceStartNotAllowedException("startForegroundService() not allowed due to mAllowStartForeground false")
        },
        dao = dao,
        // Signed out: a game the queue takes up fails with "Not signed in", without any network.
        session = RomMSession(RomMAccountStore(RuntimeEnvironment.getApplication()), OkHttpClient()),
        httpClient = OkHttpClient(),
        unpacked = app.genkan.core.UnpackedGames(java.io.File("build/unpacked-test")),
    )

    private val game = GameEntity(
        id = 321, platformId = 3, platformSlug = "psp", name = "A Game", sortTitle = "a game",
        fsName = "A Game.iso", fsSizeBytes = 1000, coverPath = null, hasMultipleFiles = false,
    )

    @Test
    fun `a refused start keeps the game queued instead of crashing`() = runBlocking {
        queue.enqueue(game).join()
        assertEquals(1, starts)
        assertEquals(DownloadState.QUEUED, dao.get(321)?.state)
    }

    @Test
    fun `a later start takes the queued game up`() = runBlocking {
        queue.enqueue(game).join()
        // Genkan comes to the front: resume asks for the service again, and this time it starts.
        refuse = false
        queue.resume().join()
        assertEquals(2, starts)
        // What the service then runs; the game leaves the queue (failing here, being signed out).
        queue.runQueue {}
        assertEquals(DownloadState.FAILED, dao.get(321)?.state)
        assertEquals("Not signed in to RomM", dao.get(321)?.error)
        assertEquals(false, queue.hasWork())
    }

    @Test
    fun `resume asks for nothing when nothing is queued`() = runBlocking {
        queue.resume().join()
        assertEquals(0, starts)
    }
}

/** The downloads table in memory. */
internal class FakeDownloadDao : DownloadDao {
    private val rows = MutableStateFlow<Map<Long, DownloadEntity>>(emptyMap())

    override fun all(): Flow<List<DownloadEntity>> = MutableStateFlow(emptyList())
    override suspend fun get(romId: Long) = rows.value[romId]
    override suspend fun next() = rows.value.values
        .filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
        .minByOrNull { it.queuedAt }
    override suspend fun upsert(download: DownloadEntity) = change(download.romId) { download }
    override suspend fun progress(romId: Long, done: Long, total: Long) = change(romId) { it?.copy(bytesDone = done, bytesTotal = total) }
    override suspend fun setState(romId: Long, state: DownloadState, error: String?) = change(romId) { it?.copy(state = state, error = error) }
    override suspend fun requeueInterrupted() {
        rows.value = rows.value.mapValues { (_, row) -> if (row.state == DownloadState.RUNNING) row.copy(state = DownloadState.QUEUED) else row }
    }
    override suspend fun delete(romId: Long) {
        rows.value = rows.value - romId
    }

    private fun change(romId: Long, update: (DownloadEntity?) -> DownloadEntity?) {
        val next = update(rows.value[romId])
        rows.value = if (next == null) rows.value - romId else rows.value + (romId to next)
    }
}
