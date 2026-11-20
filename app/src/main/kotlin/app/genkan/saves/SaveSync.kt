package app.genkan.saves

import android.content.Context
import android.os.Environment
import android.util.Log
import app.genkan.download.DownloadDao
import app.genkan.download.DownloadState
import app.genkan.library.LibraryDao
import app.genkan.play.SessionTracker
import app.genkan.privileged.Privileged
import app.genkan.privileged.FileService
import app.genkan.privileged.IFileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import app.genkan.romm.RomMApi
import app.genkan.romm.RomMClientSave
import app.genkan.romm.RomMDownloadedPayload
import app.genkan.romm.RomMNegotiatePayload
import app.genkan.romm.RomMSession
import app.genkan.romm.RomMSyncCompletePayload
import app.genkan.romm.RomMSyncOperation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import app.genkan.core.CoreCatalog
import app.genkan.core.CoreSaves
import app.genkan.core.PlayerSettings
import javax.inject.Inject
import javax.inject.Singleton

/** How a game's save stands after its last sync. */
sealed class SaveStatus {
    data class Synced(val at: Long, val what: String) : SaveStatus()
    /** Both sides changed. The device's copy was kept and also uploaded as an archive. */
    data class Conflict(val at: Long) : SaveStatus()
    data class Failed(val at: Long, val reason: String) : SaveStatus()
}

/**
 * Keeps a game's save in step with RomM through its device-sync protocol: before the game starts
 * and after it ends, once each, never in a loop. Nothing on the device is ever lost: a save about
 * to be replaced is copied to Genkan/save-backups first, a conflict leaves the device's copy in
 * place (and archives it in RomM), and a delete from the server is ignored.
 */
@Singleton
class SaveSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: RomMSession,
    private val downloads: DownloadDao,
    private val library: LibraryDao,
    private val sessions: SessionTracker,
    private val privileged: Privileged,
    private val saveFiles: SaveFileDao,
    private val player: PlayerSettings,
) {
    private val storage = Environment.getExternalStorageDirectory().path
    /** What each watched emulator's save folders held when a game started, by rom. */
    private val snapshots = mutableMapOf<Long, Map<String, SaveSnapshot.Entry>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /** Pushes each game's save when its play ends: once per session. */
    fun start() {
        if (started) return
        started = true
        scope.launch { sessions.ended.collect { sync(it.romId) } }
    }

    private val locator = SaveLocator()
    private val lock = Mutex()
    private val _status = MutableStateFlow<Map<Long, SaveStatus>>(emptyMap())
    /** The last sync of each game this run, for the details screen. */
    val status: StateFlow<Map<Long, SaveStatus>> = _status.asStateFlow()

    /** Syncs [romId]'s save; false when there is nothing to sync (no RomM, no save support). */
    suspend fun sync(romId: Long, dryRun: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val api = session.api() ?: return@withLock false
            val deviceId = session.deviceId() ?: return@withLock false
            val download = downloads.get(romId)?.takeIf { it.state == DownloadState.INSTALLED } ?: return@withLock false
            val game = library.game(romId) ?: return@withLock false
            val installed = File(download.path)
            val rom = if (installed.isDirectory) installed.walkTopDown().firstOrNull { it.isFile } ?: return@withLock false else installed
            try {
                val own = builtInTarget(game.platformSlug, rom)
                val watched = WatchedEmulator.BY_PLATFORM[game.platformSlug].takeIf { own == null }
                val io = if (own != null) {
                    DirectIo(own)
                } else if (watched != null) {
                    val files = filesFor(watched)
                    if (files == null) {
                        report(romId, SaveStatus.Failed(System.currentTimeMillis(), "Shizuku isn't running, so ${watched.emulator} saves can't be reached"))
                        return@withLock true
                    }
                    learn(romId, watched, files)
                    WatchedIo(romId, watched, files, saveFiles.paths(romId, watched.emulator), rom.nameWithoutExtension)
                } else {
                    DirectIo(locator.locate(game.platformSlug, rom) ?: return@withLock false)
                }
                run(api, deviceId, romId, io, dryRun)
            } catch (e: Exception) {
                Log.w(TAG, "Save sync of rom $romId failed", e)
                report(romId, SaveStatus.Failed(System.currentTimeMillis(), e.message ?: e.javaClass.simpleName))
            }
            true
        }
    }

    /**
     * Notes what a watched emulator's save folders hold before [romId] starts, so the files its
     * session creates or changes can be claimed as that game's save afterwards.
     */
    suspend fun beforeLaunch(romId: Long, platformSlug: String) = withContext(Dispatchers.IO) {
        if (player.isEnabled() && CoreCatalog.forPlatform(platformSlug)?.sharedSaves?.isEmpty() == true) return@withContext
        val watched = WatchedEmulator.BY_PLATFORM[platformSlug] ?: return@withContext
        val files = filesFor(watched) ?: return@withContext
        snapshots[romId] = snapshot(watched, files)
    }

    /** The built-in player's own save for the game, when it runs the game (as the launcher decides). */
    private suspend fun builtInTarget(platformSlug: String, rom: File): SaveTarget? {
        if (!player.isEnabled()) return null
        val core = CoreCatalog.forPlatform(platformSlug) ?: return null
        return CoreSaves.ownTarget(platformSlug, core, rom, File(storage))
    }

    private fun snapshot(watched: WatchedEmulator, files: IFileService): Map<String, SaveSnapshot.Entry> =
        watched.folders.flatMap { folder ->
            SaveSnapshot.parse(files.list("${filesDir(watched)}/$folder")).values.map { it.copy(path = "$folder/${it.path}") }
        }.associateBy { it.path }

    private suspend fun learn(romId: Long, watched: WatchedEmulator, files: IFileService) {
        val before = snapshots.remove(romId) ?: return
        val changed = SaveSnapshot.changed(before, snapshot(watched, files))
        if (changed.isEmpty()) return
        Log.i(TAG, "Rom $romId's session changed ${changed.joinToString()}")
        saveFiles.claim(changed.map { SaveFileEntity(watched.emulator, it, romId) })
    }

    private fun filesDir(watched: WatchedEmulator) = watched.base(storage) { File(it).exists() }

    /** Shizuku's helper for another app's Android/data; the same file work in-process otherwise. */
    private suspend fun filesFor(watched: WatchedEmulator): IFileService? =
        if (watched.privileged) privileged.files() else FileService()

    /**
     * Fetches [romId]'s newest Genkan save from RomM into [into], next to rather than over the
     * save the emulator uses: for looking at, or restoring by hand. The hash is checked.
     */
    suspend fun downloadCopy(romId: Long, into: File): Boolean = withContext(Dispatchers.IO) {
        val api = session.api() ?: return@withContext false
        val save = api.getSaves(romId, SLOT).body()?.maxByOrNull { it.updatedAt } ?: return@withContext false
        val response = api.downloadSave(save.id, deviceId = null, sessionId = null)
        val body = response.body() ?: return@withContext false
        val incoming = File(context.cacheDir, "save-${save.id}.part")
        try {
            body.byteStream().use { input -> incoming.outputStream().use { input.copyTo(it) } }
            val hash = SaveHash.ofUpload(incoming)
            Log.i(TAG, "Save ${save.id} of rom $romId: hash $hash, RomM says ${save.contentHash}")
            if (save.contentHash != null && hash != save.contentHash) return@withContext false
            if (save.fileName.endsWith(".zip")) unzipInto(incoming, into) else incoming.copyTo(File(into.apply { mkdirs() }, save.fileName), overwrite = true)
            true
        } finally {
            incoming.delete()
        }
    }

    private suspend fun run(api: RomMApi, deviceId: String, romId: Long, io: SaveIo, dryRun: Boolean) {
        val local = io.read()
        val response = api.negotiateSync(
            RomMNegotiatePayload(
                deviceId = deviceId,
                saves = listOfNotNull(local?.let {
                    RomMClientSave(romId, io.name, Instant.ofEpochMilli(it.updatedAt).toString(), it.size, it.hash, io.emulator, SLOT)
                }),
                romIds = listOf(romId),
            ),
        )
        if (response.code() == 401) session.onUnauthorized()
        val negotiated = response.body() ?: error("negotiate answered ${response.code()}")
        // Only this game's slot; anything else in the answer belongs to other devices' flows.
        val ops = negotiated.operations.filter { it.romId == romId && (it.slot == SLOT || it.slot == null && it.action == "upload") }
        Log.i(TAG, "Rom $romId (${io.name}, local ${local?.hash ?: "none"}): ${ops.joinToString { "${it.action} (${it.reason})" }.ifEmpty { "nothing to do" }}")
        if (dryRun) {
            local?.discard()
            api.completeSync(negotiated.sessionId, RomMSyncCompletePayload(0, 0))
            return
        }
        var done = 0
        var failed = 0
        var status: SaveStatus = SaveStatus.Synced(System.currentTimeMillis(), "up to date")
        for (op in ops) {
            val ok = when (op.action) {
                "upload" -> local != null && upload(api, deviceId, romId, io, local, negotiated.sessionId, SLOT).also {
                    if (it) status = SaveStatus.Synced(System.currentTimeMillis(), "uploaded")
                }
                "download" -> download(api, deviceId, io, op, negotiated.sessionId).also {
                    if (it) status = SaveStatus.Synced(System.currentTimeMillis(), "downloaded")
                }
                "conflict" -> {
                    // Keep both: the server keeps its save, the device keeps its own and archives
                    // it in RomM as a slotless save, so neither is lost and the player can choose.
                    status = SaveStatus.Conflict(System.currentTimeMillis())
                    local != null && upload(api, deviceId, romId, io, local, negotiated.sessionId, slot = null)
                }
                else -> true // no_op; a server-side delete never removes a save from the device
            }
            if (ok) done++ else failed++
        }
        local?.discard()
        api.completeSync(negotiated.sessionId, RomMSyncCompletePayload(done, failed))
        report(romId, if (failed > 0 && status !is SaveStatus.Conflict) SaveStatus.Failed(System.currentTimeMillis(), "$failed step(s) failed") else status)
    }

    private suspend fun upload(
        api: RomMApi, deviceId: String, romId: Long, io: SaveIo, local: LocalSave, sessionId: Long, slot: String?,
    ): Boolean {
        val part = MultipartBody.Part.createFormData("saveFile", io.name, local.upload.asRequestBody(OCTET))
        val response = api.uploadSave(romId, io.emulator, slot, deviceId, local.hash, sessionId, part)
        Log.i(TAG, "Uploaded ${io.name} (${local.size} bytes, slot $slot): ${response.code()}")
        return response.isSuccessful
    }

    private suspend fun download(api: RomMApi, deviceId: String, io: SaveIo, op: RomMSyncOperation, sessionId: Long): Boolean {
        val saveId = op.saveId ?: return false
        val response = api.downloadSave(saveId, deviceId, sessionId)
        val body = response.body() ?: return false.also { Log.w(TAG, "Save $saveId download answered ${response.code()}") }
        val incoming = File(context.cacheDir, "save-$saveId.part")
        body.byteStream().use { input -> incoming.outputStream().use { input.copyTo(it) } }
        try {
            val hash = SaveHash.ofUpload(incoming)
            io.replace(incoming, op.fileName)
            api.confirmSaveDownloaded(saveId, RomMDownloadedPayload(deviceId, hash))
            Log.i(TAG, "Downloaded save $saveId (${op.fileName})")
            return true
        } finally {
            incoming.delete()
        }
    }

    /** Copies the device's save aside before anything replaces it. */
    private fun backUp(target: SaveTarget) {
        if (!target.path.exists()) return
        val dir = backupDir(target.emulator, target.name.substringBeforeLast('.'))
        dir.mkdirs()
        target.path.copyRecursively(File(dir, target.path.name), overwrite = true)
        Log.i(TAG, "Backed up ${target.path} to $dir")
    }

    /** Replaces [folder]'s contents with [zip]'s files, refusing any entry that would land outside it. */
    private fun unzipInto(zip: File, folder: File) {
        val root = folder.canonicalFile
        ZipFile(zip).use { zf ->
            val entries = zf.entries().asSequence().filter { !it.isDirectory }.toList()
            entries.forEach { require(File(root, it.name).canonicalPath.startsWith(root.path + File.separator)) { "unsafe entry ${it.name}" } }
            if (root.exists()) root.deleteRecursively()
            root.mkdirs()
            for (entry in entries) {
                val out = File(root, entry.name).apply { parentFile?.mkdirs() }
                zf.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun backupDir(emulator: String, name: String): File {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return File(storage, "Genkan/save-backups/$emulator/$name/$stamp")
    }

    /** One game's save on the device: how it is read for upload, and how it is replaced. */
    private interface SaveIo {
        /** Its file name in RomM. */
        val name: String
        val emulator: String
        fun read(): LocalSave?
        /** Backs up what is there, then puts [incoming] (called [fileName] in RomM) in its place. */
        suspend fun replace(incoming: File, fileName: String)
    }

    /** A save Genkan can reach itself: Azahar's folder, RetroArch's .srm. */
    private inner class DirectIo(private val target: SaveTarget) : SaveIo {
        override val name = target.name
        override val emulator = target.emulator
        override fun read() = LocalSave.read(target, context.cacheDir)
        override suspend fun replace(incoming: File, fileName: String) {
            backUp(target)
            if (target.folder) unzipInto(incoming, target.path) else {
                target.path.parentFile?.mkdirs()
                incoming.copyTo(target.path, overwrite = true)
            }
        }
    }

    /**
     * A game's files in a watched emulator's shared save folders, reached through Shizuku and
     * staged in Genkan/.staging. One flat file travels as itself; several (or nested ones, like
     * Dolphin's GCI folders) as a zip of their paths under the emulator's files folder.
     */
    private inner class WatchedIo(
        private val romId: Long,
        private val watched: WatchedEmulator,
        private val files: IFileService,
        private val paths: List<String>,
        romStem: String,
    ) : SaveIo {
        private val single = paths.singleOrNull()?.takeIf { it.count { c -> c == '/' } == 1 }
        override val name = single?.substringAfter('/') ?: "$romStem.zip"
        override val emulator = watched.emulator
        private val base = filesDir(watched)

        override fun read(): LocalSave? {
            if (paths.isEmpty()) return null
            val stage = staging("out")
            paths.forEach { check(files.copy("$base/$it", File(stage, it).path)) { "couldn't read $it" } }
            if (single != null) {
                val file = File(stage, single)
                return LocalSave(file, SaveHash.ofFile(file), file.length(), file.lastModified(), temporary = true)
            }
            val zip = File(context.cacheDir, name)
            SaveHash.zipFolder(stage, zip)
            val newest = SaveHash.filesOf(stage).maxOf { it.second.lastModified() }
            stage.deleteRecursively()
            return LocalSave(zip, SaveHash.ofZip(zip), zip.length(), newest, temporary = true)
        }

        override suspend fun replace(incoming: File, fileName: String) {
            val backup = backupDir(watched.emulator, "rom-$romId")
            paths.forEach { files.copy("$base/$it", File(backup, it).path) }
            val stage = staging("in")
            if (fileName.endsWith(".zip")) unzipInto(incoming, stage) else incoming.copyTo(File(stage, "${watched.folders.first()}/$fileName"))
            val restored = SaveHash.filesOf(stage).map { it.first }
            // Only into the emulator's save folders, never elsewhere in its data.
            require(restored.all { path -> watched.folders.any { path.startsWith("$it/") } }) { "save outside ${watched.folders}" }
            restored.forEach { check(files.copy(File(stage, it).path, "$base/$it")) { "couldn't write $it" } }
            saveFiles.claim(restored.map { SaveFileEntity(watched.emulator, it, romId) })
            stage.deleteRecursively()
        }

        private fun staging(kind: String) = File(storage, "Genkan/.staging/$romId-$kind").apply { deleteRecursively(); mkdirs() }
    }

    private fun report(romId: Long, status: SaveStatus) = _status.update { it + (romId to status) }

    /** The device's save as it will be sent: the file itself, or its folder zipped. */
    private class LocalSave(val upload: File, val hash: String, val size: Long, val updatedAt: Long, private val temporary: Boolean) {
        /** Deletes the zip made for a folder save; a single-file save is the real save and stays. */
        fun discard() {
            if (temporary) upload.delete()
        }

        companion object {
            fun read(target: SaveTarget, cache: File): LocalSave? {
                val path = target.path
                if (!target.folder) {
                    if (!path.isFile) return null
                    return LocalSave(path, SaveHash.ofFile(path), path.length(), path.lastModified(), temporary = false)
                }
                val files = SaveHash.filesOf(path.takeIf { it.isDirectory } ?: return null)
                if (files.isEmpty()) return null
                val zip = File(cache, target.name)
                SaveHash.zipFolder(path, zip)
                // The zip's own bytes differ from RomM's view of it; the hash is over its contents.
                return LocalSave(zip, SaveHash.ofZip(zip), zip.length(), files.maxOf { it.second.lastModified() }, temporary = true)
            }
        }
    }

    companion object {
        private const val TAG = "SaveSync"
        /** The slot Genkan's saves pair on in RomM, so each sync compares against its last one. */
        const val SLOT = "genkan"
        private val OCTET = "application/octet-stream".toMediaType()
    }
}
