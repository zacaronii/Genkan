package app.genkan.library

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A RomM game the player marked as a favourite. Its own table, not a column on [GameEntity]: a
 * sync rewrites the game rows, and a favourite outlives that (and a game leaving RomM and coming
 * back).
 */
@Entity(tableName = "favourites")
data class FavouriteEntity(
    @PrimaryKey val romId: Long,
    val addedAt: Long,
)

@Dao
interface FavouriteDao {

    /** The favourite games still in the library, by title, as a console's shelf orders them. */
    @Query("SELECT games.* FROM games JOIN favourites ON favourites.romId = games.id ORDER BY games.sortTitle")
    fun games(): Flow<List<GameEntity>>

    @Query("SELECT romId FROM favourites")
    fun ids(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(favourite: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE romId = :romId")
    suspend fun remove(romId: Long)
}

/**
 * The "Favourites" shelf: the games the player marked, on the console strip after "Continue
 * playing" and hidden while there are none. RomM games only (apps and PC games have no lasting
 * id here); each stays on its own console's shelf too. A favourite deleted from the device stays
 * with the "not downloaded" state; one gone from RomM's library leaves the shelf, and comes back
 * if the game does.
 */
object Favourites {
    /** Its id on the strip (PC -1, apps -2, "Continue playing" -3). */
    const val ID = -4L
    const val SLUG = "genkan-favourites"
    const val NAME = "Favourites"

    fun platform(games: Int) = PlatformEntity(ID, SLUG, null, NAME, games, null)

    /** Whether [game] can be a favourite: a RomM game, not an app or a PC game. */
    fun canMark(game: GameEntity) = game.platformId >= 0
}
