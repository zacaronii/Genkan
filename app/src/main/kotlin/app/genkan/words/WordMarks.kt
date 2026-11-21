package app.genkan.words

import android.util.Log
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import app.genkan.anki.AnkiConfig
import app.genkan.anki.AnkiConnect
import app.genkan.anki.AnkiDao
import app.genkan.anki.AnkiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** How a word in the backlog is marked, as Hachidori marks it. */
enum class WordMarkState {
    /** Not in Anki: a solid underline. */
    NOT_IN_ANKI,

    /** In Anki but not mature yet (or queued to go there): a dashed underline. */
    LEARNING,

    /** Mature in Anki (a review card, interval 21 days or more), or marked by hand: no mark. */
    KNOWN,
}

/** A note Anki has for a word: its expression and reading, whether it's mature, and where from. */
@Entity(tableName = "anki_words", primaryKeys = ["expression", "reading", "otherDeck"])
data class AnkiWordEntity(
    val expression: String,
    val reading: String,
    val known: Boolean,
    /** From one of the extra decks, by its first field, with no reading. */
    val otherDeck: Boolean,
)

@Dao
interface AnkiWordDao {
    @Query("SELECT * FROM anki_words")
    suspend fun all(): List<AnkiWordEntity>

    @Insert
    suspend fun insert(rows: List<AnkiWordEntity>)

    @Query("DELETE FROM anki_words")
    suspend fun clear()

    @Transaction
    suspend fun replace(rows: List<AnkiWordEntity>) {
        clear()
        insert(rows)
    }
}

/**
 * Which words Anki has, for the backlog's word marks, built the way Hachidori builds its index:
 * the mining deck's notes, the mature ones being review cards with an interval of 21 days or
 * more; and, from any extra decks chosen, notes past new. Kept in a table so marks work offline
 * between refreshes, and in memory for marking: at start, every 30 minutes and after mining.
 */
@Singleton
class WordMarks @Inject constructor(
    private val dao: AnkiWordDao,
    private val ankiDao: AnkiDao,
    private val settings: AnkiSettings,
    private val anki: AnkiConnect,
    private val statuses: WordStatuses,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var started = false

    /** Expression → its notes' (reading, known); readings empty for the extra decks' notes. */
    @Volatile private var byExpression: Map<String, List<Pair<String, Boolean>>> = emptyMap()
    @Volatile private var byReading: Map<String, List<Boolean>> = emptyMap()
    @Volatile private var pendingWords: Set<String> = emptySet()

    private val _revision = MutableStateFlow(0)
    /** Changes whenever marks might have: restyle the backlog. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    /** Marks are shown only with Anki connected and the setting on: else every word would be underlined. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Starts marking; [deliveries] are cards reaching Anki, after which it asks Anki again. */
    fun start(deliveries: kotlinx.coroutines.flow.Flow<String>) {
        if (started) return
        started = true
        scope.launch { deliveries.collect { refresh() } }
        scope.launch {
            load()
            settings.config.collect { config ->
                _enabled.value = config.configured && config.marksWords
                bump()
            }
        }
        scope.launch { statuses.marks.collect { bump() } }
        scope.launch {
            while (isActive) {
                refresh()
                delay(REFRESH_MS)
            }
        }
    }

    /** The mark for a word found as [expression] / [reading]; null when marks are off. */
    fun state(expression: String, reading: String): WordMarkState? {
        if (!_enabled.value) return null
        if (statuses.status(expression) != null) return WordMarkState.KNOWN
        val notes = byExpression[expression].orEmpty().filter { (r, _) -> r.isEmpty() || reading.isEmpty() || r == reading }
        if (notes.isNotEmpty()) return if (notes.any { it.second }) WordMarkState.KNOWN else WordMarkState.LEARNING
        // A kana word whose note is under its kanji (かわいい for 可愛い): matched by the note's reading.
        if (expression.all { it in 'ぁ'..'ヿ' }) {
            byReading[expression]?.let { known -> return if (known.any { it }) WordMarkState.KNOWN else WordMarkState.LEARNING }
        }
        return if (expression in pendingWords) WordMarkState.LEARNING else WordMarkState.NOT_IN_ANKI
    }

    /** Asks Anki again (a card was mined or settings changed). */
    fun refreshSoon() {
        scope.launch { refresh() }
    }

    private suspend fun load() {
        index(dao.all())
    }

    private suspend fun refresh() = lock.withLock {
        val config = settings.current()
        if (!config.configured || !config.marksWords) return@withLock
        try {
            val rows = fetch(config)
            dao.replace(rows)
            index(rows)
            Log.i(TAG, "Anki has ${rows.size} words for marks (${rows.count { it.known }} mature)")
        } catch (e: Exception) {
            // Offline: the last index stands.
            Log.i(TAG, "Couldn't refresh word marks: ${e.message}")
            index(dao.all())
        }
    }

    private suspend fun fetch(config: AnkiConfig): List<AnkiWordEntity> {
        val deck = "\"deck:${config.deck}\""
        val all = anki.search(config, deck)
        val mature = anki.search(config, "$deck $KNOWN_SEARCH").toSet()
        val rows = mutableListOf<AnkiWordEntity>()
        for (chunk in all.chunked(CHUNK)) {
            for ((id, fields) in anki.noteFields(config, chunk)) {
                val named = fields.toMap()
                val expression = clean(named["Expression"] ?: fields.firstOrNull()?.second.orEmpty())
                if (expression.isEmpty()) continue
                val reading = clean(named["ExpressionReading"].orEmpty())
                rows += AnkiWordEntity(expression, reading, id in mature, otherDeck = false)
            }
        }
        for (other in config.markDecks.filter { it != config.deck }) {
            val name = "\"deck:$other\""
            val ids = anki.search(config, "$name -is:new")
            val otherMature = anki.search(config, "$name $KNOWN_SEARCH").toSet()
            for (chunk in ids.chunked(CHUNK)) {
                for ((id, fields) in anki.noteFields(config, chunk)) {
                    val expression = clean(fields.firstOrNull()?.second.orEmpty())
                    if (expression.isNotEmpty()) rows += AnkiWordEntity(expression, "", id in otherMature, otherDeck = true)
                }
            }
        }
        return rows.distinctBy { Triple(it.expression, it.reading, it.otherDeck) }
    }

    private suspend fun index(rows: List<AnkiWordEntity>) {
        byExpression = rows.groupBy({ it.expression }, { it.reading to it.known })
        byReading = rows.filter { it.reading.isNotEmpty() }.groupBy({ it.reading }, { it.known })
        // Mined here but not in Anki yet (queued, or sent since the last refresh): being learned.
        pendingWords = (ankiDao.queuedExpressions() + ankiDao.minedExpressions()).toSet()
        bump()
    }

    private fun bump() {
        _revision.value++
    }

    companion object {
        private const val TAG = "WordMarks"
        /** Hachidori's rule for a word you know: a review card, not relearning, interval 21 days or more. */
        const val KNOWN_SEARCH = "is:review -is:learn prop:ivl>=21"
        private const val CHUNK = 500
        private const val REFRESH_MS = 30 * 60 * 1000L

        /** A field's text: HTML and furigana brackets (漢字[かんじ]) off. */
        fun clean(field: String): String =
            WordStatuses.key(field.replace(Regex("<[^>]*>"), "").replace(Regex("\\[[^\\]]*]"), "").replace("&nbsp;", " ").replace(" ", ""))
    }
}
