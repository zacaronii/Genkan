package app.genkan.romm

import okhttp3.Interceptor
import okhttp3.Response
import java.io.InterruptedIOException

/**
 * Asks RomM once more, after [delayMs], when a GET is answered with a 5xx. RomM writes the
 * client token's row on every request, so two of Genkan's requests sent together can collide
 * there and one of them gets a 500 ("Record has changed since last read in table
 * client_tokens"); asking again a moment later succeeds.
 *
 * Only GETs are retried, since they change nothing on the server. The retry happens before any
 * of the answer's body is read, so a download already streaming is never started over: the
 * failed answer is closed and the same request sent again.
 */
class RomMRetryInterceptor(
    private val delayMs: Long = RETRY_DELAY_MS,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.method != "GET" || response.code < 500) return response
        response.close()
        try {
            sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("interrupted before retrying ${request.url.encodedPath}")
        }
        return chain.proceed(request)
    }

    companion object {
        const val RETRY_DELAY_MS = 300L
    }
}
