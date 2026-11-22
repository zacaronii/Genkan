package app.genkan.download

import android.content.Context
import android.os.Environment
import android.util.Log
import app.genkan.library.GameEntity
import app.genkan.romm.RomMApiFactory
import app.genkan.romm.RomMRetryInterceptor
import app.genkan.romm.RomMSession
import app.genkan.romm.RomMUnauthorizedException
import app.genkan.romm.describeFailure
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** The single download queue. Rows live in Room so the queue survives the process. */
@Singleton
class DownloadQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val session: RomMSession,
    httpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // The file requests carry the token too, so they can meet the same 5xx; the retry only ever
    // repeats a request whose answer was refused, never a body already streaming.
    private val downloader = Downloader(httpClient.newBuilder().addInterceptor(RomMRetryInterceptor()).build())

    val downloads: StateFlow<Map<Long, DownloadEntity>> =
        dao.all().map { rows -> rows.associateBy { it.romId } }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    @Volatile private var current: Pair<Long, Job>? = null

    val root: File get() = File(Environment.getExternalStorageDirectory(), ROOT_FOLDER)

    fun enqueue(game: GameEntity) = scope.launch {
        val existing = dao.get(game.id)
        if (existing?.state == DownloadState.INSTALLED) return@launch
        if (existing?.state == DownloadState.RUNNING) {
            // RUNNING with nothing running here is a download a process death cut off.
            if (current == null) DownloadService.start(context)
            return@launch
        }
        val fsName = game.fsName ?: game.name
        dao.upsert(
            DownloadEntity(
                romId = game.id,
                name = game.name,
                platformSlug = game.platformSlug,
                state = DownloadState.QUEUED,
                bytesTotal = game.fsSizeBytes,
                path = DownloadPaths.gamePath(root, game.platformSlug, fsName).absolutePath,
                queuedAt = System.currentTimeMillis(),
            )
        )
        DownloadService.start(context)
    }

    /** Picks up downloads a process death left queued or half done, from their .part files. */
    fun resume() = scope.launch {
        if (current != null || dao.next() == null) return@launch
        // Android may refuse a foreground service while Genkan is in the background; A on the game resumes it then.
        runCatching { DownloadService.start(context) }.onFailure { Log.i(TAG, "Downloads wait for Genkan: ${it.message}") }
    }

    /** Stops a queued or running download and throws away what it had written. */
    fun cancel(romId: Long) = scope.launch {
        current?.takeIf { it.first == romId }?.second?.cancel()
        val row = dao.get(romId) ?: return@launch
        if (row.state != DownloadState.INSTALLED) {
            dao.delete(romId)
            removeFiles(File(row.path), partsOnly = false)
        }
    }

    /** Deletes an installed game's files and forgets it. */
    fun delete(romId: Long) = scope.launch {
        val row = dao.get(romId) ?: return@launch
        removeFiles(File(row.path), partsOnly = false)
        dao.delete(romId)
    }

    /** Whether anything is queued or running. */
    suspend fun hasWork(): Boolean = dao.next() != null

    /**
     * Works through the queue until it is empty. Called by [DownloadService]; [onProgress] feeds
     * its notification. A game that fails is marked FAILED and the next one runs.
     */
    suspend fun runQueue(onProgress: (DownloadEntity) -> Unit) {
        dao.requeueInterrupted()
        while (true) {
            val next = dao.next() ?: return
            val job = scope.launch { runOne(next, onProgress) }
            current = next.romId to job
            job.join()
            current = null
        }
    }

    private suspend fun runOne(row: DownloadEntity, onProgress: (DownloadEntity) -> Unit) {
        val account = session.currentAccount()
        val api = session.api()
        if (account == null || api == null) {
            dao.setState(row.romId, DownloadState.FAILED, "Not signed in to RomM"); return
        }
        dao.setState(row.romId, DownloadState.RUNNING)
        try {
            val response = withContext(Dispatchers.IO) { api.getRom(row.romId) }
            if (response.code() == 401) throw RomMUnauthorizedException()
            val rom = response.body() ?: error("RomM answered ${response.code()} for game ${row.romId}")
            val target = File(row.path)
            val files = if (rom.hasMultipleFiles || rom.hasNestedSingleFile) {
                rom.files.orEmpty().filter(DownloadPaths::isGameFile).map { file ->
                    val url = account.baseUrl + "api/roms/${file.id}/files/content/" + encode(file.fileName)
                    Triple(url, DownloadPaths.fileInGame(target, rom.filePath ?: "", file), file.fileSizeBytes)
                }
            } else {
                listOf(Triple(account.baseUrl + "api/roms/${rom.id}/content/" + encode(rom.fileName ?: target.name), target, rom.fileSize))
            }
            val total = files.sumOf { it.third }
            var finished = 0L
            var lastReport = 0L
            for ((url, file, size) in files) {
                downloader.download(url, account.token, file, size) { bytes ->
                    val done = finished + bytes
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= PROGRESS_INTERVAL_MS || done == total) {
                        lastReport = now
                        dao.progress(row.romId, done, total)
                        onProgress(row.copy(state = DownloadState.RUNNING, bytesDone = done, bytesTotal = total))
                    }
                }
                finished += size
            }
            dao.progress(row.romId, total, total)
            dao.setState(row.romId, DownloadState.INSTALLED)
            Log.i(TAG, "Installed ${row.name} at ${row.path}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: RomMUnauthorizedException) {
            dao.setState(row.romId, DownloadState.FAILED, "Sign in to RomM again")
            session.onUnauthorized()
        } catch (e: Exception) {
            Log.w(TAG, "Download of ${row.name} failed: $e", e)
            dao.setState(row.romId, DownloadState.FAILED, describeFailure(e, "Download failed"))
        }
    }

    private suspend fun removeFiles(path: File, partsOnly: Boolean) = withContext(Dispatchers.IO) {
        coroutineScope {
            if (!partsOnly) path.deleteRecursively()
            File(path.parentFile, path.name + Downloader.PART_SUFFIX).delete()
        }
    }

    private fun encode(name: String) = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    companion object {
        private const val TAG = "DownloadQueue"
        const val ROOT_FOLDER = "ROMs"
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}
