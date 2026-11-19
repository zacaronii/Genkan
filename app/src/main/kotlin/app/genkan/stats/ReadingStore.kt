package app.genkan.stats

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A reading session of one game, as [ActivityTracker] keeps it. */
@Entity(tableName = "reading_sessions", indices = [Index("game")])
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val game: String,
    val title: String,
    val platform: String?,
    val rommRomId: Long?,
    val started: Long,
    val lastActivity: Long,
    val ended: Long? = null,
    /** What was last sent to the activity log, so only changes are sent again. */
    val pushed: String? = null,
)

/** One session's counters for one reading day (days start at 04:01). */
@Entity(tableName = "reading_daily", primaryKeys = ["sessionId", "day"])
data class ReadingDailyEntity(
    val sessionId: Long,
    val day: String,
    val game: String,
    val activeMs: Long,
    val characters: Long,
    val uniqueCharacters: Long,
    val rereadCharacters: Long,
    val lines: Long,
    val choiceCharacters: Long,
)

/** A line seen in a game: shown again in a later session, it counts as rereading. */
@Entity(tableName = "reading_seen", primaryKeys = ["game", "sourceId"])
data class ReadingSeenEntity(val game: String, val sourceId: String, val firstSeen: Long)

/** A session with its totals, for the reading log and the status line. */
data class ReadingSessionTotals(
    val id: Long,
    val game: String,
    val title: String,
    val platform: String?,
    val rommRomId: Long?,
    val started: Long,
    val lastActivity: Long,
    val ended: Long?,
    val pushed: String?,
    val activeMs: Long,
    val characters: Long,
)

/** All reading of one game. */
data class ReadingGameTotals(val activeMs: Long, val characters: Long)

/** Blocking on purpose: [ActivityTracker] runs on the reader's own background thread. */
@Dao
interface ReadingDao {

    @Query("SELECT * FROM reading_sessions WHERE game = :game AND ended IS NULL ORDER BY id DESC LIMIT 1")
    fun openSession(game: String): ReadingSessionEntity?

    @Insert
    fun insertSession(session: ReadingSessionEntity): Long

    @Query("UPDATE reading_sessions SET ended = :ended WHERE id = :id")
    fun closeSession(id: Long, ended: Long)

    @Query("UPDATE reading_sessions SET lastActivity = MAX(lastActivity, :lastActivity) WHERE id = :id")
    fun touch(id: Long, lastActivity: Long)

    @Query("SELECT * FROM reading_daily WHERE sessionId = :sessionId")
    fun daily(sessionId: Long): List<ReadingDailyEntity>

    @Query("SELECT * FROM reading_daily WHERE sessionId = :sessionId AND day = :day")
    fun dailyRow(sessionId: Long, day: String): ReadingDailyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putDaily(row: ReadingDailyEntity)

    /** True when the line was new (inserted), false when it had been seen before. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSeen(seen: ReadingSeenEntity): Long

    @Query(
        """SELECT s.id, s.game, s.title, s.platform, s.rommRomId, s.started, s.lastActivity, s.ended, s.pushed,
           COALESCE(SUM(d.activeMs), 0) AS activeMs, COALESCE(SUM(d.characters), 0) AS characters
           FROM reading_sessions s LEFT JOIN reading_daily d ON d.sessionId = s.id
           GROUP BY s.id ORDER BY s.started DESC LIMIT :limit""",
    )
    suspend fun recentTotals(limit: Int): List<ReadingSessionTotals>

    @Query("SELECT COALESCE(SUM(activeMs), 0) AS activeMs, COALESCE(SUM(characters), 0) AS characters FROM reading_daily WHERE game = :game")
    fun gameTotals(game: String): Flow<ReadingGameTotals>

    @Query("UPDATE reading_sessions SET pushed = :pushed WHERE id = :id")
    suspend fun markPushed(id: Long, pushed: String)
}

/** [ActivityStore] over Room. A session's title and game come from what's playing. */
class RoomActivityStore(
    private val dao: ReadingDao,
    private val title: String,
    private val platform: String?,
    private val rommRomId: Long?,
) : ActivityStore {

    override fun openSession(game: String): SessionRecord? = dao.openSession(game)?.let {
        SessionRecord(it.id, it.game, it.started, it.lastActivity, it.ended, sessionCounters(it.id))
    }

    override fun createSession(game: String, started: Long): Long = dao.insertSession(
        ReadingSessionEntity(game = game, title = title, platform = platform, rommRomId = rommRomId, started = started, lastActivity = started),
    )

    override fun sessionCounters(id: Long): ReadingCounters =
        dao.daily(id).fold(ReadingCounters()) { total, row -> total + row.counters() }

    override fun closeSession(id: Long, ended: Long) = dao.closeSession(id, ended)

    override fun addActivity(sessionId: Long, game: String, day: String, delta: ReadingCounters, lastActivity: Long) {
        val sum = (dao.dailyRow(sessionId, day)?.counters() ?: ReadingCounters()) + delta
        dao.putDaily(
            ReadingDailyEntity(
                sessionId, day, game, sum.activeMs, sum.characters, sum.uniqueCharacters,
                sum.rereadCharacters, sum.lines, sum.choiceCharacters,
            ),
        )
        dao.touch(sessionId, lastActivity)
    }

    override fun markSeen(game: String, sourceId: String, timestamp: Long): Boolean =
        dao.insertSeen(ReadingSeenEntity(game, sourceId, timestamp)) == -1L

    private fun ReadingDailyEntity.counters() =
        ReadingCounters(activeMs, characters, uniqueCharacters, rereadCharacters, lines, choiceCharacters)
}
