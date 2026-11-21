package app.genkan.yomikata

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.genkan.romm.KeystoreTokenCipher
import app.genkan.romm.TokenCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Where the catalog lives on GitHub (owner/name), and a read-only token while it's private. */
data class CatalogConfig(val repo: String = "", val token: String = "") {
    val configured: Boolean get() = REPO.matches(repo.trim())

    private companion object {
        val REPO = Regex("[A-Za-z0-9-]+/[A-Za-z0-9._-]+")
    }
}

sealed interface CatalogStatus {
    data object NotSet : CatalogStatus
    data object Idle : CatalogStatus
    data class Ready(val entries: Int, val updatedAt: Long) : CatalogStatus
    data class Failed(val reason: String) : CatalogStatus
}

private val Context.catalogStore: DataStore<Preferences> by preferencesDataStore(name = "yomikata_catalog")

/**
 * The Yomikata reading catalog, fetched from its GitHub repository and kept on the device: the
 * index (every id, its entry's path and hash) first, then only the entries games ask for, with
 * their tables. A cached entry is used as long as its hash matches the index, and the last copy
 * keeps working offline. The token is sealed with a Keystore key, like RomM's.
 */
@Singleton
class YomikataCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttp: OkHttpClient,
) {
    private val client = okHttp.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val cipher: TokenCipher = KeystoreTokenCipher("genkan_yomikata_catalog_token")
    private val lock = Mutex()
    private val dir: File get() = File(context.filesDir, "yomikata-catalog")

    private val _status = MutableStateFlow<CatalogStatus>(CatalogStatus.Idle)
    val status: StateFlow<CatalogStatus> = _status.asStateFlow()

    val config: Flow<CatalogConfig> = context.catalogStore.data.map {
        CatalogConfig(it[REPO].orEmpty(), it[TOKEN]?.let(cipher::decrypt).orEmpty())
    }

    suspend fun save(config: CatalogConfig) {
        context.catalogStore.edit {
            it[REPO] = config.repo.trim()
            if (config.token.isBlank()) it.remove(TOKEN) else it[TOKEN] = cipher.encrypt(config.token.trim())
            it.remove(FETCHED_AT)
        }
    }

    /** Fetches the index again when [force]d or older than [MAX_AGE_MS]; returns how many ids it lists. */
    suspend fun refresh(force: Boolean = false): Int? = lock.withLock { index(force)?.length() }

    /** The entry for a release [id] (a main id or an alias), or null when the catalog has none. */
    suspend fun entry(id: String): CatalogEntry? = lock.withLock {
        val record = index(force = false)?.optJSONObject(id) ?: return@withLock null
        val path = record.getString("path")
        val cached = File(dir, path)
        val fresh = cached.isFile && sha256(cached.readBytes()) == record.optString("sha256")
        val text = if (fresh) {
            cached.readText()
        } else {
            runCatching { fetch(path).also { save(path, it) } }
                .onFailure { Log.i(TAG, "Couldn't fetch $path: ${it.message}") }
                .getOrNull()
                ?: cached.takeIf { it.isFile }?.readText()
                ?: return@withLock null
        }
        val entry = runCatching { CatalogEntry.parse(text) }.onFailure { Log.w(TAG, "Bad entry $path: $it") }.getOrNull()
        // Tables have no hash of their own: fetched again whenever their entry changed.
        entry?.tables?.forEach { table ->
            if (!fresh || !File(dir, table).isFile) runCatching { save(table, fetch(table)) }
        }
        entry
    }

    /** An entry's tables as one, or null when one of them isn't on the device. */
    fun table(entry: CatalogEntry): CharTable? {
        if (entry.tables.isEmpty()) return null
        val files = entry.tables.map { File(dir, it).takeIf(File::isFile)?.readText() ?: return null }
        return CharTable.parse(files)
    }

    private suspend fun index(force: Boolean): JSONObject? {
        val config = config.first()
        if (!config.configured) {
            _status.value = CatalogStatus.NotSet
            return null
        }
        val file = File(dir, INDEX)
        val fetchedAt = context.catalogStore.data.first()[FETCHED_AT] ?: 0L
        var failed: String? = null
        if (force || !file.isFile || System.currentTimeMillis() - fetchedAt > MAX_AGE_MS) {
            try {
                val text = fetch(INDEX, config)
                JSONObject(text) // only a valid index replaces the cached one
                save(INDEX, text)
                val now = System.currentTimeMillis()
                context.catalogStore.edit { it[FETCHED_AT] = now }
            } catch (e: Exception) {
                Log.i(TAG, "Couldn't fetch the index: ${e.message}")
                failed = e.message ?: e.javaClass.simpleName
            }
        }
        // Offline, the last index fetched keeps working.
        val entries = runCatching { JSONObject(file.readText()).getJSONObject("entries") }.getOrNull()
        _status.value = when {
            failed != null -> CatalogStatus.Failed(failed)
            entries == null -> CatalogStatus.Failed("the cached index is unreadable")
            else -> CatalogStatus.Ready(entries.length(), context.catalogStore.data.first()[FETCHED_AT] ?: 0L)
        }
        return entries
    }

    private suspend fun fetch(path: String, given: CatalogConfig? = null): String = withContext(Dispatchers.IO) {
        val config = given ?: config.first()
        // The contents API serves private repositories with a token, and public ones without.
        val request = Request.Builder()
            .url("https://api.github.com/repos/${config.repo.trim()}/contents/$path")
            .header("Accept", "application/vnd.github.raw+json")
            .apply { if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token.trim()}") }
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 401 -> throw IOException("GitHub didn't accept the token")
                response.code == 404 -> throw IOException("$path not found (is the repository right, and the token allowed to read it?)")
                !response.isSuccessful -> throw IOException("HTTP ${response.code} for $path")
            }
            response.body.string()
        }
    }

    private suspend fun save(path: String, text: String) = withContext(Dispatchers.IO) {
        val file = File(dir, path)
        if (!file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) throw IOException("bad path $path")
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "YomikataCatalog"
        const val INDEX = "index.json"
        const val MAX_AGE_MS = 12 * 60 * 60 * 1000L
        val REPO = stringPreferencesKey("repo")
        val TOKEN = stringPreferencesKey("token")
        val FETCHED_AT = longPreferencesKey("fetched_at")
    }
}
