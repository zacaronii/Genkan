package app.genkan.words

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/** A word marked by hand, as Hachidori marks them: known, or ignored (never underlined either way). */
enum class WordStatus { KNOWN, IGNORED }

/**
 * One headword's mark. [status] null is a cleared mark kept only until Hachidori has heard of it
 * ([pendingHost]); otherwise the row goes.
 */
@Entity(tableName = "word_status")
data class WordStatusEntity(
    @PrimaryKey val headword: String,
    val status: WordStatus?,
    val updatedAt: Long,
    val pendingHost: Boolean = false,
)

@Dao
interface WordStatusDao {
    @Query("SELECT * FROM word_status WHERE status IS NOT NULL ORDER BY updatedAt DESC")
    fun marked(): Flow<List<WordStatusEntity>>

    @Query("SELECT * FROM word_status WHERE headword = :headword")
    suspend fun get(headword: String): WordStatusEntity?

    @Query("SELECT * FROM word_status WHERE pendingHost = 1")
    suspend fun pending(): List<WordStatusEntity>

    @Query("SELECT * FROM word_status WHERE status IS NOT NULL")
    suspend fun allMarked(): List<WordStatusEntity>

    @Upsert
    suspend fun upsert(rows: List<WordStatusEntity>)

    @Query("DELETE FROM word_status WHERE headword = :headword")
    suspend fun delete(headword: String)

    @Query("DELETE FROM word_status WHERE pendingHost = 0")
    suspend fun deleteSettled()

    @Query("UPDATE word_status SET pendingHost = 0 WHERE headword = :headword AND updatedAt = :updatedAt")
    suspend fun settle(headword: String, updatedAt: Long)

    @Query("DELETE FROM word_status WHERE status IS NULL AND pendingHost = 0")
    suspend fun dropCleared()

    @Query("UPDATE word_status SET pendingHost = :pending")
    suspend fun setAllPending(pending: Boolean)

    @Transaction
    suspend fun replaceSettled(rows: List<WordStatusEntity>) {
        deleteSettled()
        upsert(rows)
    }
}

/**
 * Words marked known or ignored by hand, keyed like Hachidori's `wordStatusOverrides`: the
 * dictionary headword alone (the first result's expression), trimmed and NFC-normalised, so a
 * mark covers every reading and form of the word.
 */
@Singleton
class WordStatuses @Inject constructor(private val dao: WordStatusDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Every marked word now, for the popup and the backlog's word marks. */
    val marks: StateFlow<Map<String, WordStatus>> = dao.marked()
        .map { rows -> rows.associate { it.headword to it.status!! } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val rows: Flow<List<WordStatusEntity>> = dao.marked()

    /** Whether marks go to a Hachidori host (they wait in [pendingHost] until it has them). */
    @Volatile var linked: Boolean = false

    private val _changed = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A mark changed here: something may be waiting for the host. */
    val changed: kotlinx.coroutines.flow.SharedFlow<Unit> = _changed

    fun status(headword: String): WordStatus? = marks.value[key(headword)]

    /** Marks [headword] with [status], or clears its mark (null). */
    suspend fun set(headword: String, status: WordStatus?) {
        val word = key(headword)
        if (word.isEmpty()) return
        val now = System.currentTimeMillis()
        if (status == null && !linked) dao.delete(word) else dao.upsert(listOf(WordStatusEntity(word, status, now, linked)))
        _changed.tryEmit(Unit)
    }

    /** [headword]'s mark toggled to [status]: the same status again clears it, as in Hachidori's popup. */
    suspend fun toggle(headword: String, status: WordStatus): WordStatus? {
        val next = if (status(headword) == status) null else status
        set(headword, next)
        return next
    }

    /** Marks each of [words] known (a pasted list, one per line); returns how many were new. */
    suspend fun addKnown(words: List<String>): Int {
        val now = System.currentTimeMillis()
        val fresh = words.map(::key).filter { it.isNotEmpty() }.distinct().filter { status(it) != WordStatus.KNOWN }
        dao.upsert(fresh.map { WordStatusEntity(it, WordStatus.KNOWN, now, linked) })
        _changed.tryEmit(Unit)
        return fresh.size
    }

    /** Marks from a Hachidori backup, added to ours (a word marked here keeps its mark); how many were new. */
    suspend fun importMarks(known: List<String>, ignored: List<String>): Int {
        val now = System.currentTimeMillis()
        val rows = (known.map { key(it) to WordStatus.KNOWN } + ignored.map { key(it) to WordStatus.IGNORED })
            .filter { (word, _) -> word.isNotEmpty() && status(word) == null }
            .distinctBy { it.first }
            .map { (word, status) -> WordStatusEntity(word, status, now, linked) }
        dao.upsert(rows)
        if (rows.isNotEmpty()) _changed.tryEmit(Unit)
        return rows.size
    }

    /** Marks waiting for the host. */
    suspend fun pending(): List<WordStatusEntity> = dao.pending()

    /** The host has [row]'s mark: settled, unless it changed again meanwhile. */
    suspend fun sent(row: WordStatusEntity) {
        dao.settle(row.headword, row.updatedAt)
        dao.dropCleared()
    }

    /** The host's lists replace ours, except the words still waiting to reach it. */
    suspend fun adoptHost(known: List<String>, ignored: List<String>) {
        val waiting = dao.pending().map { it.headword }.toSet()
        val now = System.currentTimeMillis()
        val rows = known.map { WordStatusEntity(key(it), WordStatus.KNOWN, now) } +
            ignored.map { WordStatusEntity(key(it), WordStatus.IGNORED, now) }
        dao.replaceSettled(rows.filter { it.headword.isNotEmpty() && it.headword !in waiting })
    }

    /** Linking a host for the first time: every local mark goes to it. */
    suspend fun queueAllForHost() = dao.setAllPending(true)

    /** Unlinked: nothing waits any more. */
    suspend fun dropPending() {
        dao.setAllPending(false)
        dao.dropCleared()
    }

    companion object {
        /** A headword as Hachidori keys it: trimmed and NFC-normalised. */
        fun key(headword: String): String = Normalizer.normalize(headword.trim(), Normalizer.Form.NFC)
    }
}
