package app.genkan.manual

import android.content.Context
import app.genkan.library.GameEntity
import app.genkan.romm.RomMApiFactory
import app.genkan.romm.RomMImageAuthInterceptor
import app.genkan.romm.RomMSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Games' manuals from the RomM server, kept in the cache once fetched (once per version of each),
 * for the details screen and for the reader during play.
 */
@Singleton
class Manuals @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: RomMSession,
    httpClient: OkHttpClient,
) {
    /** Carries the RomM sign-in, as Coil's loader does. */
    private val assetClient = httpClient.newBuilder()
        .addInterceptor(RomMImageAuthInterceptor(session::credentials))
        .build()

    private val folder: File get() = File(context.cacheDir, FOLDER)

    /** Whether [game] has a manual on the server that can be fetched now. */
    fun has(game: GameEntity): Boolean = game.manualPath != null && session.currentAccount() != null

    /** [game]'s manual when it's already in the cache, else null. */
    fun cached(game: GameEntity): File? = File(folder, fileName(game)).takeIf { it.isFile && it.length() > 0L }

    /** [game]'s manual, fetched first when it isn't cached; throws when it can't be had. */
    suspend fun file(game: GameEntity): File {
        cached(game)?.let { return it }
        val url = session.currentAccount()?.let { RomMApiFactory.assetUrl(it.baseUrl, game.manualPath) }
            ?: throw IOException("no manual on the server")
        val file = File(folder, fileName(game))
        withContext(Dispatchers.IO) {
            fetch(url, file)
            // Earlier versions of this game's manual are no longer wanted.
            folder.listFiles { old -> old.name.startsWith("${game.id}-") && old.name != file.name }?.forEach { it.delete() }
        }
        return file
    }

    private fun fetch(url: String, file: File) {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        assetClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("the server answered ${response.code}")
            part.outputStream().use { out -> response.body.byteStream().copyTo(out) }
        }
        if (!part.renameTo(file)) throw IOException("couldn't save it")
    }

    companion object {
        private const val FOLDER = "manuals"

        /**
         * The cached manual's file name: the game's id, then a hash of what identifies this
         * version of the manual, so a changed manual is fetched again. Games carry no updated
         * time, so the hash covers the manual's path (RomM adds a timestamp to some asset paths)
         * and the game's file size.
         */
        fun fileName(game: GameEntity): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("${game.manualPath}|${game.fsSizeBytes}".toByteArray())
            return "${game.id}-${digest.take(6).joinToString("") { "%02x".format(it) }}.pdf"
        }
    }
}
