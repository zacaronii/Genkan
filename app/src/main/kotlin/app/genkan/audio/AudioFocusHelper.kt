package app.genkan.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AYN's game assistant silences apps that have a window on the second display (T4), and
 * Genkan always has one. Holding transient audio focus while a top-screen window has WM focus
 * lets word and sentence audio through, so every playback goes through [playWithFocus].
 */
@Singleton
class AudioFocusHelper @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Runs [play] holding transient focus, released after [durationMs]. False if focus was denied. */
    fun playWithFocus(durationMs: Long, play: () -> Unit): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        play()
        main.postDelayed({ audioManager.abandonAudioFocusRequest(request) }, durationMs)
        return true
    }

    /** Short beep used by the companion's audio check. */
    fun playTestTone(): Boolean = playWithFocus(TEST_TONE_MS) {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, TEST_TONE_MS.toInt())
        main.postDelayed({ tone.release() }, TEST_TONE_MS + 100)
    }

    private companion object {
        const val TEST_TONE_MS = 400L
    }
}
