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
}

/**
 * Games' manuals from the RomM server, kept in the cache once fetched, for the details screen and
 * for the reader during play. A cached manual opens at once, offline too. Once a day at most, in
 * the background, the server is asked whether it changed (with the ETag it gave, kept beside the
 * file): a manual replaced on the server (another language at the same path, say) is fetched and
 * takes the cached one's place, and [replaced] says so.
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

    private val folder: File get() = File(context.cacheDir, FOLDER)

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

    override fun has(game: GameEntity): Boolean = game.manualPath != null && session.currentAccount() != null

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
        return lockFor(game).withLock { fetchFile(game, onProgress) }
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

    private class Meta(val validator: Validator, val checkedAt: Long) {
        val etag get() = validator.etag
        val lastModified get() = validator.lastModified
    }

    private fun metaFile(file: File) = File(file.path + ".meta")

    private fun readMeta(file: File): Meta? = metaFile(file).takeIf { it.isFile }?.readLines()?.let { lines ->
        Meta(Validator(lines.getOrNull(0)?.ifEmpty { null }, lines.getOrNull(1)?.ifEmpty { null }), lines.getOrNull(2)?.toLongOrNull() ?: 0L)
    }

    private fun writeMeta(file: File, validator: Validator) {
        metaFile(file).writeText("${validator.etag.orEmpty()}\n${validator.lastModified.orEmpty()}\n${now()}\n")
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
