package app.genkan.play

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One play of a RomM game. Open (no [endedAt]) while the game is running; kept in Room so a
 * session survives Genkan being killed mid-game, and so it waits here while RomM is unreachable.
 */
@Entity(tableName = "play_sessions")
data class PlaySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val romId: Long,
    val emulatorPackage: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val durationMs: Long? = null,
    val uploaded: Boolean = false,
)

data class PlayTotals(val plays: Int, val totalMs: Long, val lastPlayed: Long?)

@Dao
interface PlaySessionDao {

    @Insert
    suspend fun insert(session: PlaySessionEntity): Long

    @Query("SELECT * FROM play_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun open(): PlaySessionEntity?

    @Query("UPDATE play_sessions SET endedAt = :endedAt, durationMs = :durationMs WHERE id = :id")
    suspend fun close(id: Long, endedAt: Long, durationMs: Long)

    @Query("SELECT * FROM play_sessions WHERE endedAt IS NOT NULL AND NOT uploaded ORDER BY startedAt LIMIT :limit")
    suspend fun pending(limit: Int): List<PlaySessionEntity>

    @Query("UPDATE play_sessions SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    /** Every finished play of one game, for the bottom screen's details. */
    @Query(
        """SELECT COUNT(*) AS plays, COALESCE(SUM(durationMs), 0) AS totalMs, MAX(startedAt) AS lastPlayed
           FROM play_sessions WHERE romId = :romId AND endedAt IS NOT NULL""",
    )
    fun totals(romId: Long): Flow<PlayTotals>

    @Query("DELETE FROM play_sessions WHERE id = :id")
    suspend fun delete(id: Long)
}
