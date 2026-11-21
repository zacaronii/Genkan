package app.genkan.dictionary

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ImportStatus {
    data object Idle : ImportStatus
    data class Running(val current: String, val done: Int, val total: Int) : ImportStatus
    data class Done(val imported: Int, val skipped: Int, val failed: List<String>) : ImportStatus
}

/**
 * The imported dictionaries and the one native lookup session over them. All native calls
 * go through [lock] on one thread, so a reload never frees a session mid-lookup.
 */
@Singleton
class Dictionaries @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DictionaryDao,
) {
    private val moshi = Moshi.Builder().build()
    private val lock = Mutex()
    private val native = Dispatchers.IO.limitedParallelism(1)
    private var handle = 0L
    private var scanLength = 24
    private var frequencyDictionary = ""
    private var loaded = false

    private val importAdapter = moshi.adapter(ImportReport::class.java)
    private val hitsAdapter = moshi.adapter<List<TermHit>>(Types.newParameterizedType(List::class.java, TermHit::class.java))
    private val scannedAdapter = moshi.adapter<List<ScannedWord>>(Types.newParameterizedType(List::class.java, ScannedWord::class.java))
    private val kanjiAdapter = moshi.adapter<List<KanjiEntry>>(Types.newParameterizedType(List::class.java, KanjiEntry::class.java))
    private val stylesAdapter =
        moshi.adapter<List<DictionaryStyle>>(Types.newParameterizedType(List::class.java, DictionaryStyle::class.java))

    private val _importStatus = MutableStateFlow<ImportStatus>(ImportStatus.Idle)
    val importStatus: StateFlow<ImportStatus> = _importStatus.asStateFlow()

    val all: Flow<List<DictionaryEntity>> = dao.all()

    private val root get() = File(context.filesDir, "dictionaries")

    /**
     * Imports every Yomitan zip in [folder] (and its direct subfolders). A dictionary already
     * imported with the same revision is skipped; a new revision replaces the old one in place.
     */
    suspend fun importFrom(folder: File) = withContext(native) {
        val zips = DictionaryFiles.find(folder)
        var imported = 0
        var skipped = 0
        val failed = ArrayList<String>()
        zips.forEachIndexed { i, zip ->
            _importStatus.value = ImportStatus.Running(zip.title, i, zips.size)
            val existing = dao.byTitle(zip.title)
            if (existing != null && existing.revision == zip.revision && File(existing.folder).isDirectory) {
                skipped++
                return@forEachIndexed
            }
            val entity = importOne(zip, existing?.position)
            if (entity == null) {
                failed += zip.title
                return@forEachIndexed
            }
            lock.withLock {
                if (existing != null) {
                    dao.delete(existing.id)
                    File(existing.folder).parentFile?.deleteRecursively()
                }
                dao.insert(entity)
                loaded = false
            }
            imported++
        }
        _importStatus.value = ImportStatus.Done(imported, skipped, failed)
        Log.i(TAG, "Imported $imported dictionaries from $folder ($skipped already there, ${failed.size} failed)")
    }

    /**
     * Installs [dictionary]'s compiled folder from a Hachidori archive as it is (the engines are
     * the same); one of the same title is replaced in place, keeping its position. Returns
     * whether it was installed: false when the same revision is already here.
     */
    suspend fun install(archive: HachidoriArchive, dictionary: HachidoriArchive.Dictionary, source: String): Boolean = withContext(native) {
        val existing = dao.byTitle(dictionary.title)
        if (existing != null && existing.revision == dictionary.revision && File(existing.folder).isDirectory) return@withContext false
        val output = File(root, "d${System.currentTimeMillis()}").apply { mkdirs() }
        val folder = File(output, "dictionary")
        try {
            archive.extract(dictionary, folder)
        } catch (e: Exception) {
            output.deleteRecursively()
            throw e
        }
        val entity = DictionaryEntity(
            title = dictionary.title,
            revision = dictionary.revision,
            folder = folder.path,
            sourceName = source,
            terms = dictionary.terms,
            frequencies = dictionary.frequencies,
            pitches = dictionary.pitches,
            kanji = dictionary.kanji,
            position = existing?.position ?: (dao.lastPosition() + 1),
            enabled = existing?.enabled ?: dictionary.enabled,
            importedAt = System.currentTimeMillis(),
        )
        lock.withLock {
            if (existing != null) {
                dao.delete(existing.id)
                File(existing.folder).parentFile?.deleteRecursively()
            }
            dao.insert(entity)
            loaded = false
        }
        Log.i(TAG, "Installed ${dictionary.title} (${dictionary.revision}) from $source")
        true
    }

    /** What's installed, by title and revision. */
    suspend fun installed(): Map<String, String> = dao.list().associate { it.title to it.revision }

    private suspend fun importOne(zip: DictionaryZip, position: Int?): DictionaryEntity? {
        // Each import gets its own folder: hoshidicts names its index after the title, and
        // two dictionaries can share one.
        val output = File(root, "d${System.currentTimeMillis()}").apply { mkdirs() }
        val started = System.nanoTime()
        val report = importAdapter.fromJson(
            String(NativeDictionaries.importZip(zip.file.path.encodeToByteArray(), output.path.encodeToByteArray(), false)),
        )
        val folder = output.listFiles()?.singleOrNull { it.isDirectory }
        if (report == null || !report.success || folder == null) {
            Log.w(TAG, "Import of ${zip.file.name} failed: ${report?.error}")
            output.deleteRecursively()
            return null
        }
        Log.i(
            TAG,
            "Imported ${report.title} in ${(System.nanoTime() - started) / 1_000_000} ms: " +
                "${report.terms} terms, ${report.frequencies} frequencies, ${report.pitches} pitches, ${report.kanji} kanji",
        )
        return DictionaryEntity(
            title = zip.title,
            revision = zip.revision,
            folder = folder.path,
            sourceName = zip.file.name,
            terms = report.terms,
            frequencies = report.frequencies,
            pitches = report.pitches,
            kanji = report.kanji,
            position = position ?: (dao.lastPosition() + 1),
            importedAt = System.currentTimeMillis(),
        )
    }

    suspend fun remove(dictionary: DictionaryEntity) = withContext(native) {
        lock.withLock {
            dao.delete(dictionary.id)
            File(dictionary.folder).parentFile?.deleteRecursively()
            loaded = false
        }
    }

    suspend fun setEnabled(dictionary: DictionaryEntity, enabled: Boolean) = withContext(native) {
        lock.withLock {
            dao.setEnabled(dictionary.id, enabled)
            loaded = false
        }
    }

    /** Moves a dictionary [delta] places in the popup's order (negative: up). */
    suspend fun move(dictionary: DictionaryEntity, delta: Int) = withContext(native) {
        lock.withLock {
            val order = dao.list().toMutableList()
            val from = order.indexOfFirst { it.id == dictionary.id }
            val to = (from + delta).coerceIn(0, order.lastIndex)
            if (from < 0 || from == to) return@withLock
            order.add(to, order.removeAt(from))
            order.forEachIndexed { i, d -> if (d.position != i) dao.setPosition(d.id, i) }
            loaded = false
        }
    }

    /** The frequency dictionary to sort by, by title, when it's installed and on; null: the first one. */
    @Volatile var preferredFrequency: String? = null
        set(value) {
            if (field != value) loaded = false
            field = value
        }

    /** Looks up the start of [text]: the longest dictionary words it begins with, deinflected. */
    suspend fun lookup(text: String, maxResults: Int = MAX_RESULTS): LookupResult = withContext(native) {
        lock.withLock {
            val session = session() ?: return@withLock LookupResult(text, emptyList(), "[]")
            val json = String(
                NativeDictionaries.lookup(
                    session,
                    text.encodeToByteArray(),
                    maxResults,
                    scanLength,
                    frequencyDictionary.encodeToByteArray(),
                ),
            )
            LookupResult(text, hitsAdapter.fromJson(json).orEmpty(), json)
        }
    }

    /**
     * The words of [text] worth marking, each with its dictionary form: the longest match at each
     * kanji or katakana (see NativeDictionaries.scan). Empty while no dictionary is open.
     */
    suspend fun scan(text: String): List<ScannedWord> = withContext(native) {
        lock.withLock {
            val session = session() ?: return@withLock emptyList()
            val json = String(NativeDictionaries.scan(session, text.encodeToByteArray(), SCAN_LENGTH, frequencyDictionary.encodeToByteArray()))
            scannedAdapter.fromJson(json).orEmpty()
        }
    }

    suspend fun kanji(character: String): List<KanjiEntry> = kanjiAdapter.fromJson(kanjiJson(character)).orEmpty()

    /** The kanji dictionaries' entries for [character], as JSON for the popup page. */
    suspend fun kanjiJson(character: String): String = withContext(native) {
        lock.withLock {
            val session = session() ?: return@withLock "[]"
            String(NativeDictionaries.kanji(session, character.encodeToByteArray()))
        }
    }

    suspend fun styles(): List<DictionaryStyle> = stylesAdapter.fromJson(stylesJson()).orEmpty()

    /** Each dictionary's own CSS, as JSON for the popup page. */
    suspend fun stylesJson(): String = withContext(native) {
        lock.withLock {
            val session = session() ?: return@withLock "[]"
            String(NativeDictionaries.styles(session))
        }
    }

    suspend fun media(dictionary: String, path: String): ByteArray? = withContext(native) {
        lock.withLock {
            val session = session() ?: return@withLock null
            NativeDictionaries.media(session, dictionary.encodeToByteArray(), path.encodeToByteArray())
        }
    }

    /** The open session, rebuilt after an import or removal. Call under [lock]. */
    private suspend fun session(): Long? {
        if (!loaded) {
            if (handle != 0L) NativeDictionaries.close(handle)
            handle = 0L
            val enabled = dao.list().filter { it.enabled && File(it.folder).isDirectory }
            fun paths(of: (DictionaryEntity) -> Long) =
                enabled.filter { of(it) > 0 }.map { it.folder.encodeToByteArray() }.toTypedArray()
            if (enabled.isNotEmpty()) {
                handle = NativeDictionaries.open(
                    paths { it.terms },
                    paths { it.frequencies },
                    paths { it.pitches },
                    paths { it.kanji },
                )
                if (handle == 0L) Log.w(TAG, "A dictionary would not open")
            }
            scanLength = if (handle != 0L) NativeDictionaries.scanLength(handle) else 24
            // Results are sorted by the chosen frequency dictionary when it's here (a linked
            // Hachidori's), else by the first one, the way Yomitan does.
            val frequencyTitles = enabled.filter { it.frequencies > 0 }.map { it.title }
            frequencyDictionary = preferredFrequency?.takeIf { it in frequencyTitles } ?: frequencyTitles.firstOrNull().orEmpty()
            loaded = true
        }
        return handle.takeIf { it != 0L }
    }

    private companion object {
        const val TAG = "Dictionaries"
        const val MAX_RESULTS = 16
        /** Characters a word scan hands the engine at each start: words longer than this are rare. */
        const val SCAN_LENGTH = 12
    }
}
