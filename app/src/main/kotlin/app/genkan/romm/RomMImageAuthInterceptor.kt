// Adapted from Argosy Launcher (GPL-3.0) @ 3480817: app/src/main/kotlin/com/nendo/argosy/data/remote/romm/RomMImageAuthInterceptor.kt
package app.genkan.romm

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Carries the RomM token on image requests aimed at the signed-in server, so covers load on
 * servers that protect their assets. The header is added only when the request's scheme, host
 * and port match the connected server's own.
 */
class RomMImageAuthInterceptor(
    private val credentials: () -> Pair<String, String>?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val (baseUrl, token) = credentials() ?: return chain.proceed(request)
        if (request.header(AUTHORIZATION) != null) return chain.proceed(request)
        val server = baseUrl.toHttpUrlOrNull()
        if (server == null || !request.url.sameOriginAs(server)) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header(AUTHORIZATION, "Bearer $token").build()
        )
    }

    private fun HttpUrl.sameOriginAs(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    private companion object {
        const val AUTHORIZATION = "Authorization"
    }
}
