package app.genkan.words

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * How often a word was looked up while reading one game: a popup visit counts once, for its
 * first result's headword, as the word-wide [LookupCountEntity] counts it. [game] is the reader's
 * game key ("game:<id>"), the one the reading log uses.
 */
@Entity(tableName = "game_lookups", primaryKeys = ["game", "term", "reading"])
data class GameLookupEntity(
    val game: String,
    val term: String,
    val reading: String,
    val lookups: Int,
    val firstLookedUpAt: Long,
    val lastLookedUpAt: Long,
)

/** One game's lookups: how many different words, and how many popup visits in all. */
data class GameLookupTotals(val words: Int, val lookups: Int)

@Dao
interface GameLookupDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: GameLookupEntity): Long

    @Query("UPDATE game_lookups SET lookups = lookups + 1, lastLookedUpAt = :now WHERE game = :game AND term = :term AND reading = :reading")
    suspend fun increment(game: String, term: String, reading: String, now: Long)

    /** Counts one lookup of [term] / [reading] in [game]. */
    @Transaction
    suspend fun record(game: String, term: String, reading: String, now: Long) {
        if (insert(GameLookupEntity(game, term, reading, 1, now, now)) == -1L) increment(game, term, reading, now)
    }

    @Query("SELECT COUNT(*) AS words, COALESCE(SUM(lookups), 0) AS lookups FROM game_lookups WHERE game = :game")
    fun totals(game: String): Flow<GameLookupTotals>
}
