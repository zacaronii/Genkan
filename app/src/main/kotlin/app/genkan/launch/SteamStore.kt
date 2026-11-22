package app.genkan.launch

import android.content.Context
import app.genkan.library.GameEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What Steam's store says about a PC game: Japanese where the store has it, its default language where not. */
data class SteamDetails(
    val name: String?,
    val summary: String?,
    val genres: List<String>,
    val releaseYear: Int?,
    val developers: List<String>,
    val publishers: List<String>,
    /** The store page's backdrop, without its gradient. */
    val background: String?,
    val screenshots: List<String>,
)

/**
 * [game] with the store's text in the columns RomM's games fill, so the details show it the same
 * way: the store's name (Japanese where it has one), summary, genres, year and companies.
 */
fun SteamDetails.applyTo(game: GameEntity): GameEntity = game.copy(
    name = name ?: game.name,
    summary = summary,
    genres = genres.joinToString(" · ").ifEmpty { null },
    releaseYear = releaseYear,
    developer = developers.joinToString(", ").ifEmpty { null },
    publishers = publishers.joinToString("\n").ifEmpty { null },
)

/**
 * PC games' details from Steam's public store API (`appdetails`, no key), asked for in Japanese.
 * Each app's answer is kept in the app's files, so it's fetched once; a failed fetch is tried
 * again on the next run.
 */
@Singleton
class SteamStore internal constructor(
    private val dir: File,
    private val client: OkHttpClient,
    private val base: HttpUrl,
) {
    @Inject constructor(@ApplicationContext context: Context, client: OkHttpClient) :
        this(File(context.filesDir, "steam-store"), client, STORE.toHttpUrl())

    /** This run's answers, a missing one included, so focusing a game again doesn't ask again. */
    private val known = ConcurrentHashMap<Int, Result<SteamDetails?>>()

    suspend fun details(appId: Int): SteamDetails? = withContext(Dispatchers.IO) {
        known[appId]?.let { return@withContext it.getOrNull() }
        val result = runCatching {
            // Only the store's own "no such app" is an answer; anything else that isn't one (a
            // rate limit's `null`, an error page) is a failed fetch, tried again on the next focus.
            val japanese = cached(appId, JAPANESE)
            val parsed = parse(japanese, appId) ?: if (unknownApp(japanese, appId)) return@runCatching null else throw notAnAnswer(appId)
            // The default language only fills what the Japanese leaves empty.
            if (parsed.needsDefault()) {
                val default = cached(appId, null)
                parse(default, appId)?.let { parsed.filledFrom(it) }
                    ?: if (unknownApp(default, appId)) parsed else throw notAnAnswer(appId)
            } else parsed
        }
        // A failed fetch isn't remembered, so the next focus tries again.
        if (result.isSuccess) known[appId] = result
        result.getOrNull()
    }

    /** The store's answer for [appId] in [language] (null: its default), from the files or fetched and kept. */
    private fun cached(appId: Int, language: String?): String {
        val file = File(dir, if (language == null) "$appId.json" else "$appId.$language.json")
        if (file.isFile) return file.readText()
        val url = base.newBuilder()
            .addPathSegments("api/appdetails")
            .addQueryParameter("appids", appId.toString())
            .apply { language?.let { addQueryParameter("l", it) } }
            .build()
        val body = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Steam answered ${response.code}")
            response.body.string()
        }
        // Kept only when the store knows the app; an unknown one is asked again next run.
        if (parse(body, appId) != null) {
            dir.mkdirs()
            val part = File(file.path + ".part")
            part.writeText(body)
            part.renameTo(file)
        }
        return body
    }

    companion object {
        private const val STORE = "https://store.steampowered.com/"
        private const val JAPANESE = "japanese"
        private val YEAR = Regex("(19|20)\\d{2}")

        /** The details in an `appdetails` answer, or null when it isn't one for [appId] or the store doesn't know the app. */
        fun parse(json: String, appId: Int): SteamDetails? {
            val entry = runCatching { JSONObject(json).optJSONObject(appId.toString()) }.getOrNull() ?: return null
            if (!entry.optBoolean("success")) return null
            val data = entry.optJSONObject("data") ?: return null
            return SteamDetails(
                name = data.text("name"),
                summary = data.text("short_description"),
                genres = data.optJSONArray("genres").objects().mapNotNull { it.text("description") },
                releaseYear = data.optJSONObject("release_date")?.text("date")?.let { YEAR.find(it)?.value?.toInt() },
                developers = data.optJSONArray("developers").strings(),
                publishers = data.optJSONArray("publishers").strings(),
                background = data.text("background_raw") ?: data.text("background"),
                screenshots = data.optJSONArray("screenshots").objects().mapNotNull { it.text("path_full") },
            )
        }

        /** Whether [json] is the store saying it doesn't know [appId] (`success: false`), as opposed to no answer at all. */
        internal fun unknownApp(json: String, appId: Int): Boolean = runCatching {
            JSONObject(json).optJSONObject(appId.toString())?.let { it.has("success") && !it.optBoolean("success") }
        }.getOrNull() == true

        private fun notAnAnswer(appId: Int) = java.io.IOException("Steam's answer wasn't one for app $appId")

        private fun SteamDetails.needsDefault() = name == null || summary == null || genres.isEmpty()

        private fun SteamDetails.filledFrom(other: SteamDetails) = copy(
            name = name ?: other.name,
            summary = summary ?: other.summary,
            genres = genres.ifEmpty { other.genres },
            releaseYear = releaseYear ?: other.releaseYear,
            developers = developers.ifEmpty { other.developers },
            publishers = publishers.ifEmpty { other.publishers },
        )

        /** A field's text with HTML's tags and common entities taken out; null when that leaves nothing. */
        private fun JSONObject.text(key: String): String? =
            optString(key).takeIf { has(key) && !isNull(key) }?.let(::plain)?.takeIf { it.isNotBlank() }

        internal fun plain(html: String): String = html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: it.value }
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")
            .trim()

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).trim().takeIf(String::isNotEmpty) }
    }
}
