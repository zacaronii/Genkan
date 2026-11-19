package app.genkan.anki

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import app.genkan.audio.AacEncoder
import app.genkan.audio.GameAudioRecorder
import androidx.core.graphics.scale
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Whether a word is already a card: the popup's badge. */
enum class MinedState { NEW, QUEUED, MINED }

sealed interface AnkiStatus {
    data object NotSet : AnkiStatus
    data object Idle : AnkiStatus
    data class Unreachable(val reason: String) : AnkiStatus
    data class Sent(val count: Int) : AnkiStatus
}

/**
 * Makes cards and gets them to Anki. A card is queued in the outbox first, with its picture,
 * so mining works with Anki closed or the Wi-Fi off; the queue is sent whenever AnkiConnect
 * answers, and retried every [RETRY_MS] until it does.
 */
@Singleton
class AnkiMiner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AnkiSettings,
    private val anki: AnkiConnect,
    private val dao: AnkiDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendLock = Mutex()
    private val fieldsAdapter = Moshi.Builder().build().adapter<Map<String, String>>(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java),
    )
    private var noteFields: Pair<String, List<String>>? = null
    private var started = false

    private val _status = MutableStateFlow<AnkiStatus>(AnkiStatus.Idle)
    val status: StateFlow<AnkiStatus> = _status.asStateFlow()

    private val _deliveries = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** The expression of each card that reached Anki, so an open popup can update its badge. */
    val deliveries: SharedFlow<String> = _deliveries.asSharedFlow()

    val pending: Flow<Int> = dao.pendingCount()
    val refused: Flow<List<OutboxCard>> = dao.refused()

    private val mediaDir get() = File(context.filesDir, "anki-media").apply { mkdirs() }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                send()
                delay(RETRY_MS)
            }
        }
    }

    /**
     * Queues a card for [source], with [picture] (the top screen) and [audio] (the line's voice,
     * mono PCM at [GameAudioRecorder.SAMPLE_RATE]) when there are any.
     */
    suspend fun mine(source: MineSource, picture: Bitmap?, audio: ShortArray? = null) = withContext(Dispatchers.IO) {
        val pictureFile = picture?.let { savePicture(it) }
        val audioFile = audio?.let { saveAudio(it) }
        val fields = CardFields.lapis(source.copy(pictureFile = pictureFile, sentenceAudioFile = audioFile))
        dao.enqueue(
            OutboxCard(
                expression = source.hit.expression,
                fieldsJson = fieldsAdapter.toJson(fields),
                pictureFile = pictureFile,
                createdAt = System.currentTimeMillis(),
                audioFile = audioFile,
            ),
        )
        Log.i(TAG, "Queued a card for ${source.hit.expression}")
        scope.launch { send() }
    }

    /** Each expression's badge, from the outbox and the local record of mined words. */
    suspend fun states(expressions: List<String>): Map<String, MinedState> {
        if (expressions.isEmpty()) return emptyMap()
        val mined = dao.minedAmong(expressions).toSet()
        val queued = dao.queuedExpressions().toSet()
        return expressions.associateWith {
            when (it) {
                in mined -> MinedState.MINED
                in queued -> MinedState.QUEUED
                else -> MinedState.NEW
            }
        }
    }

    /** Asks Anki which of [expressions] the deck already has, and records them. */
    suspend fun refreshFromAnki(expressions: List<String>) {
        val config = settings.current()
        if (!config.configured) return
        try {
            for (expression in expressions.distinct()) {
                if (anki.findNotes(config, "Expression", expression).isNotEmpty()) {
                    dao.mined(MinedWord(expression, System.currentTimeMillis()))
                }
            }
        } catch (e: Exception) {
            // Offline: the local record stands.
        }
    }

    /** Sends what's queued; stops at the first sign Anki can't be reached. */
    suspend fun send() = sendLock.withLock {
        val config = settings.current()
        if (!config.configured) {
            _status.value = AnkiStatus.NotSet
            return@withLock
        }
        var sent = 0
        while (true) {
            val batch = dao.pending(BATCH)
            if (batch.isEmpty()) break
            for (card in batch) {
                try {
                    val fields = CardFields.forNoteType(fieldsAdapter.fromJson(card.fieldsJson).orEmpty(), fieldsOf(config))
                    storeMedia(config, card.pictureFile)
                    storeMedia(config, card.audioFile)
                    anki.addNote(config, fields, TAGS)
                    onDelivered(card)
                    sent++
                } catch (e: AnkiConnectError) {
                    if (e.isDuplicate) {
                        // Already in the deck: as good as sent.
                        onDelivered(card)
                    } else {
                        dao.refuse(card.id, e.message.orEmpty())
                        Log.w(TAG, "Anki refused the card for ${card.expression}: ${e.message}")
                    }
                } catch (e: IOException) {
                    _status.value = AnkiStatus.Unreachable(e.message ?: e.javaClass.simpleName)
                    return@withLock
                }
            }
        }
        _status.value = if (sent > 0) AnkiStatus.Sent(sent) else AnkiStatus.Idle
        if (sent > 0) Log.i(TAG, "Sent $sent cards to Anki")
    }

    private suspend fun onDelivered(card: OutboxCard) {
        dao.mined(MinedWord(card.expression, System.currentTimeMillis()))
        dao.sent(card.id)
        card.pictureFile?.let { File(mediaDir, it).delete() }
        card.audioFile?.let { File(mediaDir, it).delete() }
        _deliveries.tryEmit(card.expression)
    }

    /** The note type's fields, asked once per note type. */
    private suspend fun fieldsOf(config: AnkiConfig): List<String>? {
        noteFields?.let { (type, fields) -> if (type == config.noteType) return fields }
        return anki.modelFieldNames(config, config.noteType).also { noteFields = config.noteType to it }
    }

    /** The fields already point at the file ([sound:…], <img>); Anki only needs it stored. */
    private suspend fun storeMedia(config: AnkiConfig, name: String?) {
        if (name == null) return
        val file = File(mediaDir, name)
        if (file.exists()) anki.storeMediaFile(config, name, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
    }

    private fun saveAudio(samples: ShortArray): String? = try {
        val name = "genkan_${System.currentTimeMillis()}.m4a"
        AacEncoder.encode(samples, GameAudioRecorder.SAMPLE_RATE, File(mediaDir, name))
        name
    } catch (e: Exception) {
        Log.w(TAG, "Sentence audio couldn't be encoded: $e")
        null
    }

    private fun savePicture(frame: Bitmap): String {
        val width = minOf(PICTURE_WIDTH, frame.width)
        val scaled = if (width == frame.width) frame else frame.scale(width, frame.height * width / frame.width)
        val name = "genkan_${System.currentTimeMillis()}.webp"
        File(mediaDir, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, PICTURE_QUALITY, it) }
        return name
    }

    private companion object {
        const val TAG = "AnkiMiner"
        const val RETRY_MS = 30_000L
        const val BATCH = 20
        const val PICTURE_WIDTH = 640
        const val PICTURE_QUALITY = 80
        val TAGS = listOf("genkan")
    }
}
