package app.genkan.dictionary

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One imported Yomitan dictionary. [folder] is hoshidicts' index, kept in its own layout so
 * Hachidori data can line up with it later. One dictionary can hold several kinds (JMdict has
 * terms only, a pitch dictionary has pitch only); [position] orders them in the popup.
 */
@Entity(tableName = "dictionaries")
data class DictionaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val revision: String,
    val folder: String,
    val sourceName: String,
    val terms: Long,
    val frequencies: Long,
    val pitches: Long,
    val kanji: Long,
    val position: Int,
    val enabled: Boolean = true,
    val importedAt: Long,
)

@Dao
interface DictionaryDao {

    @Query("SELECT * FROM dictionaries ORDER BY position")
    fun all(): Flow<List<DictionaryEntity>>

    @Query("SELECT * FROM dictionaries ORDER BY position")
    suspend fun list(): List<DictionaryEntity>

    @Query("SELECT * FROM dictionaries WHERE title = :title LIMIT 1")
    suspend fun byTitle(title: String): DictionaryEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM dictionaries")
    suspend fun lastPosition(): Int

    @Insert
    suspend fun insert(dictionary: DictionaryEntity): Long

    @Query("DELETE FROM dictionaries WHERE id = :id")
    suspend fun delete(id: Long)
}
