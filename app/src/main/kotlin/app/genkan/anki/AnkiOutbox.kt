package app.genkan.anki

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A card waiting to reach Anki. Made offline, it waits here with its picture; sent, it is
 * deleted. A card AnkiConnect refuses for good (a duplicate, a missing deck) is kept with
 * [error] so it isn't retried forever and the reason can be shown.
 */
@Entity(tableName = "anki_outbox")
data class OutboxCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expression: String,
    /** The note's fields as JSON, already filled. */
    val fieldsJson: String,
    /** Media file in Genkan's anki-media folder, attached as the Picture field. */
    val pictureFile: String?,
    val createdAt: Long,
    /** The sentence audio (.m4a) in the same folder. */
    val audioFile: String? = null,
    val error: String? = null,
)

/** A word already in the deck: the "In Anki" badge works offline from this. */
@Entity(tableName = "anki_mined")
data class MinedWord(
    @PrimaryKey val expression: String,
    val at: Long,
)

@Dao
interface AnkiDao {

    @Insert
    suspend fun enqueue(card: OutboxCard): Long

    @Query("SELECT * FROM anki_outbox WHERE error IS NULL ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<OutboxCard>

    @Query("SELECT COUNT(*) FROM anki_outbox WHERE error IS NULL")
    fun pendingCount(): Flow<Int>

    @Query("SELECT * FROM anki_outbox WHERE error IS NOT NULL ORDER BY createdAt DESC")
    fun refused(): Flow<List<OutboxCard>>

    @Query("UPDATE anki_outbox SET error = :error WHERE id = :id")
    suspend fun refuse(id: Long, error: String)

    @Query("DELETE FROM anki_outbox WHERE id = :id")
    suspend fun sent(id: Long)

    @Query("SELECT expression FROM anki_outbox WHERE error IS NULL")
    suspend fun queuedExpressions(): List<String>

    @Upsert
    suspend fun mined(word: MinedWord)

    @Query("SELECT expression FROM anki_mined")
    suspend fun minedExpressions(): List<String>

    @Query("SELECT expression FROM anki_mined WHERE expression IN (:expressions)")
    suspend fun minedAmong(expressions: List<String>): List<String>
}
