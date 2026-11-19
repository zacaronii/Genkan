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

    /** RomM returns asset paths like "/assets/...?ts=2026-10-05 01:54:31" with a raw space. */
    fun assetUrl(baseUrl: String, path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return baseUrl.trimEnd('/') + "/" + path.trimStart('/').replace(" ", "%20")
    }
}
