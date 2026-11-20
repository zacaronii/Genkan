package app.genkan.stats

import android.util.Log
import app.genkan.romm.RomMApiFactory
import app.genkan.romm.RomMImageAuthInterceptor
import app.genkan.romm.RomMSession
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a game's cover to mochibridge (`POST /api/covers`), which puts it on the game's
 * kechimochi entry if that has none. The cover is the one Genkan shows: RomM's own large cover,
 * fetched with Genkan's RomM sign-in. RomM's `url_cover` isn't used, since covers set by hand
 * in RomM leave it empty or stale.
 */
@Singleton
class CoverUpload @Inject constructor(
    private val session: RomMSession,
    okHttp: OkHttpClient,
) {
    private val client = okHttp.newBuilder()
        .addInterceptor(RomMImageAuthInterceptor(session::credentials))
        .build()

    sealed interface Result {
        data object Sent : Result
        /** Nothing to send, or mochibridge refused this cover; trying again won't help. */
        data class Refused(val detail: String) : Result
        data class Later(val reason: String) : Result
    }

    /** [entry] is the session posts' entry object, as JSON. */
    suspend fun upload(config: ReadingLogConfig, romId: Long, entry: String): Result = try {
        when (val cover = fetchCover(romId)) {
            null -> Result.Refused("RomM has no cover for rom $romId")
            else -> post(config, entry, cover)
        }
    } catch (e: Exception) {
        Result.Later(e.message ?: e.javaClass.simpleName)
    }

    private class Cover(val bytes: ByteArray, val type: String)

    private suspend fun fetchCover(romId: Long): Cover? {
        val baseUrl = session.credentials()?.first ?: throw IllegalStateException("not signed in to RomM")
        val api = session.api() ?: throw IllegalStateException("not signed in to RomM")
        val rom = api.getRom(romId)
        if (!rom.isSuccessful) throw IllegalStateException("RomM answered ${rom.code()} for rom $romId")
        val body = rom.body() ?: return null
        val url = RomMApiFactory.assetUrl(baseUrl, body.coverLarge ?: body.coverSmall) ?: return null
        client.newCall(Request.Builder().url(url).build()).execute().use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw IllegalStateException("RomM answered ${it.code} for the cover")
            val bytes = it.body?.bytes() ?: return null
            if (bytes.size > MAX_BYTES) return null
            return Cover(bytes, it.header("Content-Type") ?: "image/jpeg")
        }
    }

    private fun post(config: ReadingLogConfig, entry: String, cover: Cover): Result {
        val extension = when {
            "png" in cover.type -> "png"
            "webp" in cover.type -> "webp"
            else -> "jpg"
        }
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("client", "genkan")
            .addFormDataPart("entry", entry)
            .addFormDataPart("image", "cover.$extension", cover.bytes.toRequestBody(cover.type.toMediaType()))
            .build()
        val request = Request.Builder()
            .url(config.coversEndpoint)
            .apply { if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token}") }
            .post(form)
            .build()
        return client.newCall(request).execute().use {
            when {
                it.isSuccessful -> Result.Sent
                it.code == 400 -> Result.Refused(it.body?.string().orEmpty().take(300))
                it.code == 401 -> Result.Later("mochibridge refused the API token")
                else -> Result.Later("HTTP ${it.code}")
            }
        }
    }

    private companion object {
        /** mochibridge takes covers up to 5 MB. */
        const val MAX_BYTES = 5 * 1024 * 1024
    }
}
