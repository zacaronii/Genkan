package app.genkan.words

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How often a word was looked up, as Hachidori counts it: per dictionary headword and reading
 * of the first result (trimmed, NFC). [pendingHost] lookups haven't reached a Hachidori host yet.
 */
@Entity(tableName = "lookup_counts", primaryKeys = ["term", "reading"])
data class LookupCountEntity(
    val term: String,
    val reading: String,
    val lookupCount: Int,
    val firstLookedUpAt: Long,
    val lastLookedUpAt: Long,
    val pendingHost: Int = 0,
)

@Dao
interface LookupCountDao {
    @Query("SELECT * FROM lookup_counts WHERE term = :term AND reading = :reading")
    suspend fun get(term: String, reading: String): LookupCountEntity?

    @Query("SELECT * FROM lookup_counts WHERE pendingHost > 0")
    suspend fun pending(): List<LookupCountEntity>

    @Query("SELECT COUNT(*) FROM lookup_counts")
    suspend fun size(): Int

    @Upsert
    suspend fun upsert(row: LookupCountEntity)

    @Query("UPDATE lookup_counts SET pendingHost = MAX(0, pendingHost - 1) WHERE term = :term AND reading = :reading")
    suspend fun sentOne(term: String, reading: String)

    @Query("UPDATE lookup_counts SET pendingHost = 0")
    suspend fun dropPending()

    @Query("DELETE FROM lookup_counts")
    suspend fun clear()

    @Transaction
    suspend fun record(term: String, reading: String, now: Long, linked: Boolean): LookupCountEntity {
        val row = get(term, reading)
        val next = row?.copy(lookupCount = row.lookupCount + 1, lastLookedUpAt = now, pendingHost = row.pendingHost + if (linked) 1 else 0)
            ?: LookupCountEntity(term, reading, 1, now, now, if (linked) 1 else 0)
        upsert(next)
        return next
    }
}

@Singleton
class LookupCounts @Inject constructor(private val dao: LookupCountDao) {

    /** Whether lookups go to a Hachidori host too. */
    @Volatile var linked: Boolean = false

    private val _changed = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A lookup was counted here: it may be waiting for the host. */
    val changed: kotlinx.coroutines.flow.SharedFlow<Unit> = _changed

    /** One lookup of [term] / [reading] (a popup visit); its count now. */
    suspend fun record(term: String, reading: String): Int {
        val t = WordStatuses.key(term)
        if (t.isEmpty()) return 0
        return dao.record(t, WordStatuses.key(reading), System.currentTimeMillis(), linked).lookupCount
            .also { _changed.tryEmit(Unit) }
    }

    suspend fun count(term: String, reading: String): Int = dao.get(WordStatuses.key(term), WordStatuses.key(reading))?.lookupCount ?: 0

    /** What the host counts for a word: ours becomes that plus the lookups still on their way. */
    suspend fun adoptHost(term: String, reading: String, hostCount: Int, first: Long?, last: Long?) {
        val row = dao.get(term, reading)
        val pending = row?.pendingHost ?: 0
        val now = System.currentTimeMillis()
        dao.upsert(
            LookupCountEntity(
                term, reading, hostCount + pending,
                minOf(first ?: now, row?.firstLookedUpAt ?: now), maxOf(last ?: 0, row?.lastLookedUpAt ?: 0), pending,
            ),
        )
    }

    /**
     * Counts from a Hachidori backup: each word keeps the larger of its two counts and the
     * earlier first lookup. They stay here: Hachidori has no way to take past lookups.
     */
    suspend fun importRows(rows: List<Triple<Pair<String, String>, Int, Pair<Long?, Long?>>>): Int {
        var changed = 0
        for ((key, count, times) in rows) {
            val term = WordStatuses.key(key.first)
            val reading = WordStatuses.key(key.second)
            val row = dao.get(term, reading)
            if (row != null && row.lookupCount >= count) continue
            val now = System.currentTimeMillis()
            dao.upsert(
                LookupCountEntity(
                    term, reading, count,
                    minOf(times.first ?: now, row?.firstLookedUpAt ?: now),
                    maxOf(times.second ?: 0, row?.lastLookedUpAt ?: 0),
                    row?.pendingHost ?: 0,
                ),
            )
            changed++
        }
        return changed
    }

    suspend fun pending(): List<LookupCountEntity> = dao.pending()

    suspend fun sentOne(term: String, reading: String) = dao.sentOne(term, reading)

    suspend fun dropPending() = dao.dropPending()

    suspend fun size(): Int = dao.size()

    suspend fun reset() = dao.clear()
}
