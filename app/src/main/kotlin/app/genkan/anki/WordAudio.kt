package app.genkan.anki

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A recording of a word for its card's ExpressionAudio, from an audio server that answers as
 * Yomitan's custom audio source does (`{"type": "audioSourceList", "audioSources": [{name, url}]}`):
 * the first recording, text-to-speech only when there's nothing else. The source is a URL with
 * `{term}` and `{reading}` in it, set in Anki's settings.
 */
@Singleton
class WordAudio @Inject constructor(okHttp: OkHttpClient) {
    private val client = okHttp.newBuilder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /**
     * The word's recording saved in [dir] as `genkan_word_*.mp3`, its file name; null when the
     * source isn't set, can't be reached or has nothing for the word: the card goes without it.
     */
    fun fetch(source: String, term: String, reading: String, dir: File): String? {
        val list = listUrl(source, term, reading) ?: return null
        return try {
            val body = client.newCall(Request.Builder().url(list).build()).execute().use { if (it.isSuccessful) it.body.string() else null }
                ?: return null
            val url = choose(body) ?: return null
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val name = "genkan_word_${System.currentTimeMillis()}.mp3"
                File(dir, name).outputStream().use { out -> response.body.byteStream().copyTo(out) }
                name
            }
        } catch (e: Exception) {
            Log.i(TAG, "No word audio: $e")
            null
        }
    }

    companion object {
        private const val TAG = "WordAudio"
        private const val TIMEOUT_S = 5L

        /** [source] with the word in it, or null when it isn't set or has no `{term}`. */
        fun listUrl(source: String, term: String, reading: String): String? {
            val template = source.trim()
            if (template.isEmpty() || "{term}" !in template) return null
            return template.replace("{term}", encode(term)).replace("{reading}", encode(reading.ifEmpty { term }))
        }

        /** The recording to take from an audio source list: the first that isn't text-to-speech, else the first. */
        fun choose(listJson: String): String? {
            val sources = runCatching { JSONObject(listJson).optJSONArray("audioSources") }.getOrNull() ?: return null
            val entries = (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
                .mapNotNull { e -> e.optString("url").takeIf { it.isNotEmpty() }?.let { e.optString("name") to it } }
            return (entries.firstOrNull { (name, url) -> !isTts(name, url) } ?: entries.firstOrNull())?.second
        }

        private fun isTts(name: String, url: String) = name.startsWith("TTS") || "/tts" in url

        private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    }
}
