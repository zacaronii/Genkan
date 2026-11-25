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

/** What the catalog has for a release id. */
sealed interface EntryLookup {
    data class Found(val entry: CatalogEntry) : EntryLookup

    /** The catalog doesn't list the id (or isn't set up): nothing to ask again for. */
    data object NotListed : EntryLookup

    /** The id is listed (or may be) but its entry couldn't be had; [retry]: whether asking again can help. */
    data class Failed(val reason: String, val retry: Boolean) : EntryLookup
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

    /** The entry for a release [id] (a main id or an alias), or null when the catalog has none or can't give it. */
    suspend fun entry(id: String): CatalogEntry? = (lookup(id) as? EntryLookup.Found)?.entry

    /** The entry for a release [id], or why there is none: not listed, or couldn't be had. */
    suspend fun lookup(id: String): EntryLookup = lock.withLock {
        val entries = index(force = false)
            ?: return@withLock if (_status.value == CatalogStatus.NotSet) EntryLookup.NotListed
            else EntryLookup.Failed("the catalog's index can't be read", retry = true)
        val record = entries.optJSONObject(id)
            ?: (if (fetchAgainIfStale()) index(force = false)?.optJSONObject(id) else null)
            ?: return@withLock EntryLookup.NotListed
        val path = record.getString("path")
        val cached = File(dir, path)
        val fresh = cached.isFile && sha256(cached.readBytes()) == record.optString("sha256")
        val text = if (fresh) {
            cached.readText()
        } else {
            val fetched = runCatching { fetch(path).also { save(path, it) } }
                .onFailure { Log.i(TAG, "Couldn't fetch $path: ${it.message}") }
            fetched.getOrNull()
                ?: cached.takeIf { it.isFile }?.readText()
                ?: return@withLock EntryLookup.Failed("couldn't fetch $path: ${fetched.exceptionOrNull()?.message}", retry = true)
        }
        val entry = runCatching { CatalogEntry.parse(text) }
            .getOrElse {
                Log.w(TAG, "Bad entry $path: $it")
                return@withLock EntryLookup.Failed("$path doesn't read as an entry: ${it.message}", retry = false)
            }
        // Tables have no hash of their own: fetched again whenever their entry changed.
        entry.tables.forEach { table ->
            if (!fresh || !File(dir, table).isFile) runCatching { save(table, fetch(table)) }
        }
        EntryLookup.Found(entry)
    }

    /**
     * The script for a release [id], its source and data files fetched from the same repository as
     * the entries (or the device's copy, while its hash matches the index's, and offline).
     */
    suspend fun script(id: String): ScriptLookup = lock.withLock {
        val first = scriptNow(id)
        val behind = IndexAge.mayBeBehind(first, catalogSet = _status.value != CatalogStatus.NotSet, listed = hasEntry(id))
        if (behind && fetchAgainIfStale()) scriptNow(id) else first
    }

    /**
     * Fetches the index again when a game asked for something it doesn't have and it's older than
     * [IndexAge.STALE_MS] (at most that often); whether a new one came.
     */
    private suspend fun fetchAgainIfStale(): Boolean {
        val now = System.currentTimeMillis()
        val fetchedAt = context.catalogStore.data.first()[FETCHED_AT] ?: 0L
        if (!IndexAge.fetchAgain(now, fetchedAt, staleTriedAt)) return false
        staleTriedAt = now
        Log.i(TAG, "Not in the index fetched ${(now - fetchedAt) / 60_000} min ago: fetching it again")
        index(force = true)
        return (context.catalogStore.data.first()[FETCHED_AT] ?: 0L) != fetchedAt
    }

    /** Whether the device's index has an entry for [id] (a main id or an alias). */
    private fun hasEntry(id: String): Boolean =
        runCatching { JSONObject(File(dir, INDEX).readText()).getJSONObject("entries").has(id) }.getOrDefault(false)

    /** When [fetchAgainIfStale] last tried. */
    private var staleTriedAt = 0L

    private suspend fun scriptNow(id: String): ScriptLookup {
        index(force = false) ?: return if (_status.value == CatalogStatus.NotSet) ScriptLookup.NotListed
            else ScriptLookup.Failed("the catalog's index can't be read")
        val record = runCatching { JSONObject(File(dir, INDEX).readText()).optJSONObject("scripts")?.optJSONObject(id) }.getOrNull()
            ?: return ScriptLookup.NotListed
        val script = runCatching { CatalogScript.parse(id, record) }
            .getOrElse { return ScriptLookup.Failed("its index record doesn't read: ${it.message}") }
        return script.files { path, sha256 -> file(path, sha256) }
    }

    /** A catalog file's bytes: the device's copy while it matches [sha256], else fetched again (and checked). */
    private suspend fun file(path: String, sha256: String): ByteArray {
        val cached = File(dir, path)
        if (cached.isFile && sha256(cached.readBytes()) == sha256) return cached.readBytes()
        val fetched = runCatching { fetchBytes(path) }.getOrElse {
            Log.i(TAG, "Couldn't fetch $path: ${it.message}")
            // Offline: the last copy, even one the index has moved past.
            return cached.takeIf { f -> f.isFile }?.readBytes() ?: throw IOException("couldn't fetch $path: ${it.message}")
        }
        if (sha256(fetched) != sha256) throw IndexBehind("$path doesn't match the index's hash")
        saveBytes(path, fetched)
        return fetched
    }

    /** The catalog files at [paths] that are on the device, by path (a layout's table sets). */
    fun files(paths: Collection<String>): Map<String, String> =
        paths.mapNotNull { path -> File(dir, path).takeIf(File::isFile)?.let { path to it.readText() } }.toMap()

    /**
     * The entry for [id] as cached on the device, or null: never the network, so the details
     * screen can ask. It may be older than the catalog; that's fine for saying how text is read.
     */
    fun cachedEntry(id: String): CatalogEntry? = runCatching {
        val entries = JSONObject(File(dir, INDEX).readText()).getJSONObject("entries")
        val path = entries.optJSONObject(id)?.getString("path") ?: return null
        CatalogEntry.parse(File(dir, path).takeIf(File::isFile)?.readText() ?: return null)
    }.getOrNull()

    /** Whether the device's index lists a script for [id]: never the network, for the details screen. */
    fun cachedHasScript(id: String): Boolean =
        runCatching { JSONObject(File(dir, INDEX).readText()).optJSONObject("scripts")?.has(id) == true }.getOrDefault(false)

    /** An entry's table files as one text (later files win, as parsing them in order does), or null. */
    fun tableText(entry: CatalogEntry): String? {
        if (entry.tables.isEmpty()) return null
        val files = entry.tables.map { File(dir, it).takeIf(File::isFile)?.readText() ?: return null }
        return files.joinToString("\n")
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

    private suspend fun fetchBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        val config = config.first()
        val request = Request.Builder()
            .url("https://api.github.com/repos/${config.repo.trim()}/contents/$path")
            .header("Accept", "application/vnd.github.raw+json")
            .apply { if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token.trim()}") }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $path")
            response.body.bytes()
        }
    }

    private suspend fun saveBytes(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val file = File(dir, path)
        if (!file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) throw IOException("bad path $path")
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
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
