package app.genkan.download

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

enum class DownloadState { QUEUED, RUNNING, INSTALLED, FAILED }

/** One game's download, kept after it finishes as the record of where the game lives. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val romId: Long,
    val name: String,
    val platformSlug: String,
    val state: DownloadState,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    /** File for a single-file game, folder for a multi-file one. Set from the start. */
    val path: String,
    val error: String? = null,
    val queuedAt: Long,
)

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads")
    fun all(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE romId = :romId")
    suspend fun get(romId: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED', 'RUNNING') ORDER BY queuedAt LIMIT 1")
    suspend fun next(): DownloadEntity?

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("UPDATE downloads SET bytesDone = :done, bytesTotal = :total WHERE romId = :romId")
    suspend fun progress(romId: Long, done: Long, total: Long)

    @Query("UPDATE downloads SET state = :state, error = :error WHERE romId = :romId")
    suspend fun setState(romId: Long, state: DownloadState, error: String? = null)

    /** A process death mid-download leaves RUNNING rows; they resume from their .part files. */
    @Query("UPDATE downloads SET state = 'QUEUED' WHERE state = 'RUNNING'")
    suspend fun requeueInterrupted()

    @Query("DELETE FROM downloads WHERE romId = :romId")
    suspend fun delete(romId: Long)
}
