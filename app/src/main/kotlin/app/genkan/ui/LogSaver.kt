package app.genkan.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import app.genkan.anki.AnkiSettings
import app.genkan.memory.SampleInbox
import app.genkan.romm.RomMSession
import app.genkan.romm.RomMState
import app.genkan.stats.ReadingLogSettings
import app.genkan.yomikata.YomikataCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings › Advanced › Save a log: the last [LINES] lines of Genkan's own log (an app reads only
 * its own), with every secret it holds and anything that looks like a key or token taken out
 * (LogRedaction), into Download on this device. Genkan never sends it anywhere.
 */
@Singleton
class LogSaver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val romm: RomMSession,
    private val anki: AnkiSettings,
    private val inbox: SampleInbox,
    private val catalog: YomikataCatalog,
    private val readingLog: ReadingLogSettings,
) {
    /** Writes the log; its name in Download, or why it couldn't. */
    suspend fun save(nowMs: Long = System.currentTimeMillis()): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val log = ProcessBuilder("logcat", "-d", "-t", LINES.toString()).redirectErrorStream(true).start()
                .inputStream.bufferedReader().use { it.readText() }
            val text = LogRedaction.redact(log, secrets())
            val name = "Genkan-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs)) + ".txt"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Download wouldn't take the file")
            resolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            name
        }
    }

    /** Every secret Genkan keeps: the RomM token and the servers' keys and tokens. */
    private suspend fun secrets(): List<String> = listOfNotNull(
        (romm.state.value as? RomMState.SignedIn)?.account?.token,
        anki.current().apiKey,
        inbox.config.first().token,
        catalog.config.first().token,
        readingLog.config.first().token,
    ).filter { it.isNotBlank() }

    companion object {
        const val LINES = 2_000
    }
}
