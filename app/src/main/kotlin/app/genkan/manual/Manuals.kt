package app.genkan.manual

import android.content.Context
import android.util.Log
import app.genkan.library.GameEntity
import app.genkan.romm.RomMApiFactory
import app.genkan.romm.RomMImageAuthInterceptor
import app.genkan.romm.RomMSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Where a game's manual comes from: what [PlayManual] needs of [Manuals]. */
interface ManualSource {
    /** Whether [game] has a manual on the server that can be fetched now. */
    fun has(game: GameEntity): Boolean

    /** [game]'s manual when it's already in the cache, else null. */
    fun cached(game: GameEntity): File?

    /** [game]'s manual, fetched first when it isn't cached; throws when it can't be had. */
    suspend fun file(game: GameEntity, onProgress: (Float?) -> Unit = {}): File

    /** [owner] (the details, the reader) has [file] open now, or none: a manual in use is never replaced. */
    fun use(owner: String, file: File?) {}

    /** Whether [game]'s manual was replaced by a newer one since it was last opened; true once. */
    fun takeReplacedNote(game: GameEntity): Boolean = false
}

/**
 * Games' manuals from the RomM server, kept in the cache once fetched, for the details screen and
 * for the reader during play. A cached manual opens at once, offline too. Once a day at most, in
 * the background, the server is asked whether it changed (with the ETag it gave, kept beside the
 * file): a manual replaced on the server (another language at the same path, say) is fetched and
 * takes the cached one's place, and [replaced] says so (and [takeReplacedNote], on the next open).
 * The folder is in app storage the system doesn't clear, so it keeps its own house: see [removeGone]
 * and [trim]; signing out empties it.
 */
@Singleton
class Manuals @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: RomMSession,
    httpClient: OkHttpClient,
) : ManualSource {
    /** Carries the RomM sign-in, as Coil's loader does. */
    private val assetClient = httpClient.newBuilder()
        .addInterceptor(RomMImageAuthInterceptor(session::credentials))
        .build()

    private val folder: File get() = File(context.noBackupFilesDir, FOLDER)

    /** Where manuals were kept before: the cache folder, which Android may empty. Moved once. */
    private val oldFolder: File get() = File(context.cacheDir, FOLDER)

    /** The files each owner has open (the details, the reader). */
    private val inUse = java.util.concurrent.ConcurrentHashMap<String, File>()

    /** Games whose manual was replaced since it was last opened, for a note on the next open. */
    private val replacedNotes = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Called after a manual is stored (fetched by hand or ahead): the folder's housekeeping runs. */
    @Volatile var onStored: (() -> Unit)? = null

    /**
     * One fetch or check of a game's manual at a time (the details and the reader can ask for the
     * same one at once). Opening a cached manual takes none: it never waits on the network.
     */
    private val locks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
    private fun lockFor(game: GameEntity) = locks.getOrPut(game.id) { Mutex() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The clock the daily check goes by; replaceable in tests. */
    internal var now: () -> Long = System::currentTimeMillis

    private val _replaced = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    /**
     * A game (its id) whose cached manual was replaced by a newer one from the server. Nothing
     * listens yet: the next open shows the new manual. The manual prefetch will use it.
     */
    val replaced: SharedFlow<Long> = _replaced

    init {
        scope.launch {
            moveFromCache()
            // Signing out empties the folder, like the art: another account's manuals aren't kept.
            session.signedOut.collect { clear() }
        }
    }

    override fun has(game: GameEntity): Boolean = game.manualPath != null && session.currentAccount() != null

    override fun use(owner: String, file: File?) {
        if (file == null) inUse.remove(owner) else {
            inUse[owner] = file
            markOpened(file)
        }
    }

    override fun takeReplacedNote(game: GameEntity): Boolean = replacedNotes.remove(game.id)

    private fun inUse(file: File) = inUse.values.any { it == file }

    override fun cached(game: GameEntity): File? = File(folder, fileName(game)).takeIf { it.isFile && it.length() > 0L }

    /**
     * [game]'s manual, fetched first when it isn't cached; throws when it can't be had. A second
     * caller waits for a fetch under way and then finds the file cached.
     */
    override suspend fun file(game: GameEntity, onProgress: (Float?) -> Unit): File {
        cached(game)?.let { cached ->
            // Opened as it is, at once; whether the server has a newer one is asked after, in the background.
            scope.launch { refresh(game) }
            return cached
        }
        // Opened by hand, it's always fetched, even with the folder over its cap: the trim follows.
        return lockFor(game).withLock { fetchFile(game, onProgress) }.also { onStored?.invoke() }
    }

    /**
     * [game]'s manual fetched ahead (not opened): nothing when it's cached, or when the server's file
     * is over [maxBytes] (it's logged, and opening it by hand still works). True when one was stored.
     */
    suspend fun prefetch(game: GameEntity, maxBytes: Long): Boolean = lockFor(game).withLock {
        if (cached(game) != null) return@withLock false
        withContext(Dispatchers.IO) {
            val url = session.currentAccount()?.let { RomMApiFactory.assetUrl(it.baseUrl, game.manualPath) } ?: return@withContext false
            val length = try {
                assetClient.newCall(Request.Builder().url(url).head().build()).execute().use { it.header("Content-Length")?.toLongOrNull() }
            } catch (e: IOException) {
                return@withContext false
            }
            if (length != null && length > maxBytes) {
                Log.i(TAG, "Manual of rom ${game.id} not fetched ahead: ${length / MB} MB, over ${maxBytes / MB} MB")
                return@withContext false
            }
            runCatching { fetchFile(game) {} }.isSuccess
        }
    }

    private suspend fun fetchFile(game: GameEntity, onProgress: (Float?) -> Unit): File {
        // A caller that waited for another's fetch finds it cached.
        cached(game)?.let { return it }
        val file = File(folder, fileName(game))
        return withContext(Dispatchers.IO) {
            try {
                val url = session.currentAccount()?.let { RomMApiFactory.assetUrl(it.baseUrl, game.manualPath) }
                    ?: throw IOException("no manual on the server")
                // A manual cached under an earlier name (when the name carried the game's update
                // time) is kept, without a download, when the server's file is its length.
                earlier(game)?.let { old ->
                    if (adopt(url, old, file)) return@withContext file
                }
                fetch(url, file, onProgress)?.let { validator -> writeMeta(file, validator) }
            } catch (e: IOException) {
                // This version can't be had (offline, say): an earlier one of the game's manual
                // still opens, until a fetch succeeds.
                return@withContext earlier(game) ?: throw e
            }
            // Earlier versions of this game's manual (and what was kept beside them) are no longer wanted.
            folder.listFiles { old -> old.name.startsWith("${game.id}-") && !old.name.startsWith(file.name) }?.forEach { it.delete() }
            file
        }
    }

    /** What a check found: not due yet, unchanged, replaced by a newer manual, or the server couldn't be asked. */
    enum class Refresh { NOT_DUE, UNCHANGED, REPLACED, FAILED }

    /**
     * Asks the server whether [game]'s cached manual changed, once a day at most: with its ETag (or
     * Last-Modified), so an unchanged one costs a 304. A manual cached before these were kept is
     * asked about with a HEAD, and kept when the server's length is its own. A newer one is fetched
     * and takes the cached one's place (a reader that has the old one open keeps it until it reopens).
     */
    suspend fun refresh(game: GameEntity): Refresh = lockFor(game).withLock {
        withContext(Dispatchers.IO) { checkNow(game) }.also { if (it != Refresh.NOT_DUE) Log.i(TAG, "Manual of rom ${game.id} checked: $it") }
    }

    private fun checkNow(game: GameEntity): Refresh {
        val file = File(folder, fileName(game)).takeIf { it.isFile && it.length() > 0L } ?: return Refresh.NOT_DUE
        val meta = readMeta(file)
        if (meta != null && now() - meta.checkedAt < CHECK_EVERY_MS) return Refresh.NOT_DUE
        // Open in the details or the reader: never replaced under it; still due, asked about next time.
        if (inUse(file)) return Refresh.NOT_DUE
        val url = session.currentAccount()?.let { RomMApiFactory.assetUrl(it.baseUrl, game.manualPath) } ?: return Refresh.NOT_DUE
        return try {
            if (meta?.etag == null && meta?.lastModified == null) {
                // Nothing to ask with (cached before validators were kept, or the server gave
                // none): the same length on the server is taken as the same file, and kept.
                assetClient.newCall(Request.Builder().url(url).head().build()).execute().use { head ->
                    if (head.isSuccessful && head.header("Content-Length")?.toLongOrNull() == file.length()) {
                        writeMeta(file, Validator(head.header("ETag"), head.header("Last-Modified")))
                        return Refresh.UNCHANGED
                    }
                }
            }
            val request = Request.Builder().url(url).apply {
                meta?.etag?.let { header("If-None-Match", it) }
                meta?.lastModified?.takeIf { meta.etag == null }?.let { header("If-Modified-Since", it) }
            }.build()
            val part = File(file.path + ".part")
            val validator = assetClient.newCall(request).execute().use { response ->
                if (response.code == 304 && meta != null) {
                    writeMeta(file, meta.validator)
                    return Refresh.UNCHANGED
                }
                if (!response.isSuccessful) throw IOException("the server answered ${response.code}")
                checkType(response)
                part.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                checkPdf(part)
                Validator(response.header("ETag"), response.header("Last-Modified"))
            }
            if (!part.renameTo(file)) throw IOException("couldn't save it")
            writeMeta(file, validator)
            replacedNotes.add(game.id)
            _replaced.tryEmit(game.id)
            Refresh.REPLACED
        } catch (e: IOException) {
            // Offline, or the server is away: the cached manual stays as it is, and is asked about next time.
            File(file.path + ".part").delete()
            Refresh.FAILED
        }
    }

    /** Takes [old] as [file] when a HEAD finds the server's manual at [url] the same length; false when it isn't. */
    private fun adopt(url: String, old: File, file: File): Boolean =
        assetClient.newCall(Request.Builder().url(url).head().build()).execute().use { head ->
            if (!head.isSuccessful || head.header("Content-Length")?.toLongOrNull() != old.length() || !old.renameTo(file)) return@use false
            writeMeta(file, Validator(head.header("ETag"), head.header("Last-Modified")))
            true
        }

    /** An answer that says it's a web page (a proxy's sign-in page, an error page) is no manual. */
    private fun checkType(response: okhttp3.Response) {
        val type = response.header("Content-Type") ?: return
        if (type.trim().startsWith("text/html", ignoreCase = true)) throw IOException("the server sent a page, not a manual")
    }

    /** What arrived must be a PDF before it's kept: else it's thrown away and the cached manual stays. */
    private fun checkPdf(part: File) {
        val head = ByteArray(4)
        val n = part.inputStream().use { it.read(head) }
        if (n < 4 || String(head, Charsets.US_ASCII) != "%PDF") {
            part.delete()
            throw IOException("what the server sent isn't a PDF")
        }
    }

    /** What the server gave to ask about a file again: its ETag and Last-Modified. */
    private data class Validator(val etag: String?, val lastModified: String?)

    private class Meta(val validator: Validator, val checkedAt: Long, val openedAt: Long) {
        val etag get() = validator.etag
        val lastModified get() = validator.lastModified
    }

    private fun metaFile(file: File) = File(file.path + ".meta")

    private fun readMeta(file: File): Meta? = metaFile(file).takeIf { it.isFile }?.readLines()?.let { lines ->
        Meta(
            Validator(lines.getOrNull(0)?.ifEmpty { null }, lines.getOrNull(1)?.ifEmpty { null }),
            lines.getOrNull(2)?.toLongOrNull() ?: 0L,
            lines.getOrNull(3)?.toLongOrNull() ?: 0L,
        )
    }

    /** Keeps [validator] and the check's time beside [file], and when it was last opened ([openedAt], else as it was). */
    private fun writeMeta(file: File, validator: Validator, openedAt: Long? = null) {
        val opened = openedAt ?: readMeta(file)?.openedAt ?: 0L
        val checked = readMeta(file)?.takeIf { openedAt != null }?.checkedAt ?: now()
        metaFile(file).writeText("${validator.etag.orEmpty()}\n${validator.lastModified.orEmpty()}\n$checked\n$opened\n")
    }

    /** Notes [file] was opened now, for the trim (least recently opened goes first). */
    private fun markOpened(file: File) {
        if (!file.isFile) return
        val meta = readMeta(file)
        // A file with no meta yet gets none of a check: it stays due, as before.
        if (meta == null) metaFile(file).writeText("\n\n0\n${now()}\n") else writeMeta(file, meta.validator, openedAt = now())
    }

    /** The manuals in the folder by game, with when each was last opened. */
    private fun stored(): List<Triple<Long, File, Long>> =
        folder.listFiles { f -> f.name.endsWith(".pdf") }.orEmpty().mapNotNull { f ->
            f.name.substringBefore('-').toLongOrNull()?.let { id -> Triple(id, f, readMeta(f)?.openedAt ?: 0L) }
        }

    /** How much the folder holds. */
    fun size(): Long = folder.listFiles().orEmpty().sumOf { it.length() }

    /** Removes the manuals of games no longer in the library ([libraryIds]), never one in use. */
    fun removeGone(libraryIds: Set<Long>) {
        stored().filter { (id, file) -> id !in libraryIds && !inUse(file) }.forEach { (_, file) -> remove(file) }
    }

    /**
     * Brings the folder under [cap]: manuals of games that aren't [installed] go, least recently
     * opened first, never one in use; installed games' manuals are never trimmed.
     */
    fun trim(installed: Set<Long>, cap: Long) {
        var size = size()
        for ((_, file) in stored().filter { (id, file) -> id !in installed && !inUse(file) }.sortedBy { it.third }) {
            if (size <= cap) break
            size -= file.length() + metaFile(file).length()
            remove(file)
        }
    }

    private fun remove(file: File) {
        Log.i(TAG, "Removed manual ${file.name}")
        file.delete()
        metaFile(file).delete()
    }

    /** Empties the folder (signed out). */
    private fun clear() {
        folder.listFiles()?.forEach { it.delete() }
        replacedNotes.clear()
    }

    /**
     * Moves the manuals (and what's kept beside them) from the cache folder, once, by their exact
     * names; a move cut off halfway finishes on the next start. A file already in the new folder wins.
     */
    internal fun moveFromCache() {
        val old = oldFolder.takeIf { it.isDirectory } ?: return
        folder.mkdirs()
        old.listFiles()?.forEach { f ->
            val target = File(folder, f.name)
            if (target.exists() || f.name.endsWith(".part")) f.delete() else if (!f.renameTo(target)) {
                f.copyTo(target)
                f.delete()
            }
        }
        old.delete()
    }

    /** The newest earlier version of [game]'s manual in the folder, whole (not a part, not empty), if any. */
    private fun earlier(game: GameEntity): File? =
        folder.listFiles { old -> old.name.startsWith("${game.id}-") && old.name.endsWith(".pdf") && old.length() > 0L }
            ?.maxByOrNull { it.lastModified() }

    /**
     * Downloads [url] to [file] through a .part file, so a cut-off download is never opened,
     * telling [onProgress] the share done when the server gives the length (null when it doesn't).
     */
    private suspend fun fetch(url: String, file: File, onProgress: (Float?) -> Unit): Validator? {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        val call = assetClient.newCall(Request.Builder().url(url).build())
        var validator: Validator? = null
        coroutineScope {
            // Cancelled, the call is too, from another thread: a stalled read ends at once
            // instead of at its timeout, and lets go of the lock.
            val watch = launch { try { awaitCancellation() } finally { call.cancel() } }
            try {
                validator = fetchBody(call, part, onProgress)
            } catch (e: Throwable) {
                // A half file is no use. Cancelled, the read fails as the socket closes: that's the cancel.
                part.delete()
                currentCoroutineContext().ensureActive()
                throw e
            } finally {
                watch.cancel()
            }
        }
        checkPdf(part)
        if (!part.renameTo(file)) throw IOException("couldn't save it")
        return validator
    }

    private suspend fun fetchBody(call: okhttp3.Call, part: File, onProgress: (Float?) -> Unit): Validator =
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("the server answered ${response.code}")
            checkType(response)
            val length = response.body.contentLength().takeIf { it > 0 }
            onProgress(if (length == null) null else 0f)
            part.outputStream().use { out ->
                val input = response.body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    // A cancelled fetch stops here instead of downloading to the end.
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    done += read
                    if (length != null) onProgress((done.toFloat() / length).coerceAtMost(1f))
                }
            }
            Validator(response.header("ETag"), response.header("Last-Modified"))
        }

    companion object {
        private const val TAG = "Manuals"
        private const val FOLDER = "manuals"
        private const val MB = 1L shl 20
        /** Said once, on the next open of a manual the server replaced. */
        const val REPLACED_NOTE = "A newer manual was fetched"
        /** How often a cached manual is asked about on the server, at most. */
        const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000

        /**
         * The cached manual's file name: the game's id, then a hash of the manual's path without
         * the rom's update time (`ts`, which moves for any change to the game, not only its
         * manual) and the game's file size. A manual replaced at the same path is found by
         * [refresh], not by its name.
         */
        fun fileName(game: GameEntity): String {
            val path = game.manualPath?.let(::withoutVersion)
            val digest = MessageDigest.getInstance("SHA-1").digest("$path|${game.fsSizeBytes}".toByteArray())
            return "${game.id}-${digest.take(6).joinToString("") { "%02x".format(it) }}.pdf"
        }

        /** [path] without its `ts` query parameter. */
        fun withoutVersion(path: String): String {
            val base = path.substringBefore('?')
            val kept = path.substringAfter('?', "").split('&').filter { it.isNotEmpty() && !it.startsWith("ts=") }
            return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
        }
    }
}
