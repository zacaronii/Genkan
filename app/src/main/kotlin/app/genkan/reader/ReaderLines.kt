package app.genkan.reader

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One line of the backlog, filed under the game it came from. */
@Entity(tableName = "reader_lines", indices = [Index("gameKey", "at")])
data class ReaderLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameKey: String,
    val text: String,
    val at: Long,
)

/** The hook thread chosen as a game's dialogue. */
@Entity(tableName = "hook_threads")
data class HookThreadEntity(
    @PrimaryKey val gameKey: String,
    val thread: String,
    val hookCode: String,
)

@Dao
interface ReaderDao {

    /** The newest [limit] lines, oldest first. */
    @Query("SELECT * FROM (SELECT * FROM reader_lines WHERE gameKey = :gameKey ORDER BY at DESC, id DESC LIMIT :limit) ORDER BY at, id")
    fun recent(gameKey: String, limit: Int): Flow<List<ReaderLineEntity>>

    @Query("SELECT * FROM reader_lines WHERE gameKey = :gameKey ORDER BY at DESC, id DESC LIMIT 1")
    suspend fun last(gameKey: String): ReaderLineEntity?

    @Insert
    suspend fun insert(line: ReaderLineEntity): Long

    @Query("UPDATE reader_lines SET text = :text, at = :at WHERE id = :id")
    suspend fun replace(id: Long, text: String, at: Long)

    @Query("SELECT * FROM hook_threads WHERE gameKey = :gameKey")
    fun chosenThread(gameKey: String): Flow<HookThreadEntity?>

    @Upsert
    suspend fun chooseThread(choice: HookThreadEntity)

    @Query("DELETE FROM hook_threads WHERE gameKey = :gameKey")
    suspend fun forgetThread(gameKey: String)
}
