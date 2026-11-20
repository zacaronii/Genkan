package app.genkan.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import app.genkan.core.BuiltInPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records what the game plays into a ring of the last [SECONDS] seconds: a card's sentence audio
 * is cut from it afterwards. A game in the built-in player hands its sound over itself ([feed]);
 * other emulators are recorded through the screen capture's MediaProjection. Genkan's own
 * sounds are left out by UID (T8: excludeUid, not usage matching, and never
 * allowAudioPlaybackCapture=false), with usage matching as the fallback PlayTranslate keeps.
 */
@Singleton
class GameAudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    player: BuiltInPlayer,
) {
    private val lock = Any()
    private val ring = AudioRing(SAMPLE_RATE, SECONDS)
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var fromPlayer = false

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    init {
        scope.launch {
            player.game.collect { game ->
                fromPlayer = game != null
                _running.value = record != null || fromPlayer
            }
        }
    }

    /**
     * Mono 16-bit little-endian [pcm] at [sampleRate] from the built-in player, its newest sample
     * played at [endMs]; resampled to the ring's rate.
     */
    fun feed(pcm: ByteArray, sampleRate: Int, endMs: Long) {
        if (sampleRate <= 0 || pcm.size < 2) return
        val input = ShortArray(pcm.size / 2) { ((pcm[it * 2].toInt() and 0xFF) or (pcm[it * 2 + 1].toInt() shl 8)).toShort() }
        val output = Resample.linear(input, sampleRate, SAMPLE_RATE)
        synchronized(lock) { ring.write(output, output.size, endMs) }
    }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by hasPermission() first
    fun start(projection: MediaProjection) {
        if (record != null || !hasPermission()) return
        val config = runCatching {
            AudioPlaybackCaptureConfiguration.Builder(projection).excludeUid(Process.myUid()).build()
        }.getOrElse {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val rec = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(SAMPLE_RATE * 2)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Game audio can't be recorded: $e")
            return
        }
        rec.startRecording()
        record = rec
        _running.value = true
        thread = Thread({
            val chunk = ShortArray(SAMPLE_RATE / 10)
            while (record === rec) {
                val n = rec.read(chunk, 0, chunk.size)
                // The built-in player's own sound is cleaner than a recording of it.
                if (n > 0 && !fromPlayer) synchronized(lock) { ring.write(chunk, n, System.currentTimeMillis()) }
            }
        }, "GameAudioRecorder").apply { start() }
        Log.i(TAG, "Recording game audio")
    }

    fun stop() {
        val rec = record ?: return
        record = null
        thread?.join(500)
        thread = null
        runCatching { rec.stop() }
        rec.release()
        _running.value = fromPlayer
        Log.i(TAG, "Stopped recording game audio")
    }

    /** The audio between two wall-clock times, trimmed of silence; null when it was silent. */
    fun clip(fromMs: Long, toMs: Long): ShortArray? {
        val raw = synchronized(lock) { ring.clip(fromMs, toMs) }
        val trimmed = ClipTrimmer.trim(raw, SAMPLE_RATE)
        val peak = if (raw.isEmpty()) -120.0 else ClipTrimmer.dbfs(raw, 0, raw.size)
        Log.i(TAG, "Clip of ${toMs - fromMs} ms: ${raw.size} samples at ${"%.0f".format(peak)} dBFS, ${trimmed?.size ?: 0} after trimming")
        return trimmed
    }

    companion object {
        private const val TAG = "GameAudioRecorder"
        const val SAMPLE_RATE = 44_100
        const val SECONDS = 60
    }
}
