// Adapted from Argosy Launcher (GPL-3.0) @ 3480817: app/src/main/kotlin/com/nendo/argosy/data/remote/romm/RomMApiFactory.kt
// (User/client certificate trust and the reachability probe are left out.)
package app.genkan.romm

import com.squareup.moshi.Moshi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

private const val DOWNLOAD_STALL_TIMEOUT_SECONDS = 900
private const val CONNECT_TIMEOUT_SECONDS = 30L
private const val READ_WRITE_TIMEOUT_SECONDS = 60L

object RomMApiFactory {

    private val moshi: Moshi = Moshi.Builder().build()

    fun create(baseUrl: String, token: String?, client: OkHttpClient = OkHttpClient()): RomMApi {
        val authInterceptor = Interceptor { chain ->
            val request = if (token != null) {
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }

        // A large ROM can stall for minutes while the server zips a folder game; the 900 s
        // window is Argosy's, found on real libraries.
        val downloadTimeoutInterceptor = Interceptor { chain ->
            val path = chain.request().url.encodedPath
            if (path.contains("/content") || path.endsWith("/api/roms")) {
                chain.withReadTimeout(DOWNLOAD_STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .withWriteTimeout(DOWNLOAD_STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .proceed(chain.request())
            } else {
                chain.proceed(chain.request())
            }
        }

        val httpClient = client.newBuilder()
            .addInterceptor(authInterceptor)
            .addInterceptor(downloadTimeoutInterceptor)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(RomMApi::class.java)
    }

    /**
     * "romm.example.com" or "https://romm.example.com/" -> "https://romm.example.com/".
     * Retrofit needs the trailing slash; a bare host is assumed to be HTTPS.
     */
    fun normalizeBaseUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        return "$withScheme/"
    }

    /**
     * A file under the server's resources folder, as a server-relative asset path like the
     * covers' `path_cover_large`, so [assetUrl] and the image loader's authorization treat it the
     * same way. ScreenScraper's `*_path` fields are relative ("roms/13/283/physical/physical.png")
     * and gain the "/assets/romm/resources/" prefix; a path that already starts with "/assets/"
     * (a screenshot or a manual) is kept as it is. Genkan stores the result, never the raw path.
     *
     * An absolute URL gives null: these fields should only ever hold paths on the RomM server,
     * and a URL here could be ScreenScraper's, which carries credentials.
     */
    fun resourcePath(path: String?): String? {
        val trimmed = path?.trim()
        if (trimmed.isNullOrEmpty() || "://" in trimmed) return null
        val relative = trimmed.trimStart('/')
        return if (relative.startsWith("assets/")) "/$relative" else "$RESOURCES_PREFIX$relative"
    }

    private const val RESOURCES_PREFIX = "/assets/romm/resources/"

    /** RomM returns asset paths like "/assets/...?ts=2026-10-05 01:54:31" with a raw space. */
    fun assetUrl(baseUrl: String, path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return baseUrl.trimEnd('/') + "/" + path.trimStart('/').replace(" ", "%20")
    }
}
