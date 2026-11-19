package app.genkan.download

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Streams one URL to a file, resuming from `<file>.part` with a Range request. The file only
 * gets its real name once the byte count matches, so a half-written game never looks installed.
 */
class Downloader(private val client: OkHttpClient) {

    /**
     * [onBytes] gets the bytes written so far for this file, including bytes resumed from an
     * earlier attempt. Throws on HTTP errors, short bodies and cancellation; the .part survives.
     */
    suspend fun download(
        url: String,
        token: String?,
        target: File,
        expectedBytes: Long?,
        onBytes: suspend (Long) -> Unit,
    ) {
        if (target.exists() && (expectedBytes == null || target.length() == expectedBytes)) {
            onBytes(target.length()); return
        }
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + PART_SUFFIX)
        val resumeFrom = if (part.exists()) part.length() else 0L

        val request = Request.Builder().url(url)
            .header("Accept-Encoding", "identity")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 416 && expectedBytes != null && resumeFrom == expectedBytes) {
                finish(part, target); onBytes(resumeFrom); return
            }
            if (response.code == 401) throw app.genkan.romm.RomMUnauthorizedException()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${target.name}")
            // 206 continues the part file; a 200 means the server ignored the range, so start over.
            val append = response.code == 206 && resumeFrom > 0
            var written = if (append) resumeFrom else 0L
            onBytes(written)
            FileOutputStream(part, append).use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var sinceReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        sinceReport += read
                        if (sinceReport >= REPORT_EVERY_BYTES) {
                            onBytes(written); sinceReport = 0
                        }
                    }
                }
            }
            if (expectedBytes != null && written != expectedBytes) {
                throw IOException("${target.name}: got $written of $expectedBytes bytes")
            }
            finish(part, target)
            onBytes(written)
        }
    }

    private fun finish(part: File, target: File) {
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("Could not move ${part.name} into place")
    }

    companion object {
        const val PART_SUFFIX = ".part"
        private const val BUFFER_BYTES = 256 * 1024
        private const val REPORT_EVERY_BYTES = 4L * 1024 * 1024
    }
}
