package app.genkan.manual

import android.content.Context
import app.genkan.library.GameEntity
import app.genkan.romm.RomMApiFactory
import app.genkan.romm.RomMImageAuthInterceptor
import app.genkan.romm.RomMSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * Games' manuals from the RomM server, kept in the cache once fetched (once per version of each),
 * for the details screen and for the reader during play.
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

    /** One fetch at a time: the details and the reader can ask for the same manual at once. */
    private val lock = Mutex()

    override fun has(game: GameEntity): Boolean = game.manualPath != null && session.currentAccount() != null

    override fun cached(game: GameEntity): File? = File(folder, fileName(game)).takeIf { it.isFile && it.length() > 0L }

    /**
     * [game]'s manual, fetched first when it isn't cached; throws when it can't be had. A second
     * caller waits for a fetch under way and then finds the file cached.
     */
    override suspend fun file(game: GameEntity, onProgress: (Float?) -> Unit): File = lock.withLock {
        cached(game)?.let { return@withLock it }
        val file = File(folder, fileName(game))
        withContext(Dispatchers.IO) {
            try {
                val url = session.currentAccount()?.let { RomMApiFactory.assetUrl(it.baseUrl, game.manualPath) }
                    ?: throw IOException("no manual on the server")
                fetch(url, file, onProgress)
            } catch (e: IOException) {
                // This version can't be had (offline, say): an earlier one of the game's manual
                // still opens, until a fetch succeeds.
                return@withContext earlier(game) ?: throw e
            }
            // Earlier versions of this game's manual are no longer wanted.
            folder.listFiles { old -> old.name.startsWith("${game.id}-") && old.name != file.name }?.forEach { it.delete() }
            file
        }
    }

    /** The newest earlier version of [game]'s manual in the folder, whole (not a part, not empty), if any. */
    private fun earlier(game: GameEntity): File? =
        folder.listFiles { old -> old.name.startsWith("${game.id}-") && old.name.endsWith(".pdf") && old.length() > 0L }
            ?.maxByOrNull { it.lastModified() }

    /**
     * Downloads [url] to [file] through a .part file, so a cut-off download is never opened,
     * telling [onProgress] the share done when the server gives the length (null when it doesn't).
     */
    private suspend fun fetch(url: String, file: File, onProgress: (Float?) -> Unit) {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        assetClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("the server answered ${response.code}")
            val length = response.body.contentLength().takeIf { it > 0 }
            onProgress(if (length == null) null else 0f)
            try {
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
            } catch (e: CancellationException) {
                part.delete()
                throw e
            }
        }
        if (!part.renameTo(file)) throw IOException("couldn't save it")
    }

    companion object {
        private const val FOLDER = "manuals"

        /**
         * The cached manual's file name: the game's id, then a hash of what identifies this
         * version of the manual, so a changed manual is fetched again. The hash covers the
         * manual's path, which carries the rom's update time (LibrarySync.art), and the game's
         * file size.
         */
        fun fileName(game: GameEntity): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("${game.manualPath}|${game.fsSizeBytes}".toByteArray())
            return "${game.id}-${digest.take(6).joinToString("") { "%02x".format(it) }}.pdf"
        }
    }
}
