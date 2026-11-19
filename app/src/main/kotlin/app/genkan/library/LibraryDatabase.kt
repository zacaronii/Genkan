package app.genkan.library

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "platforms")
data class PlatformEntity(
    @PrimaryKey val id: Long,
    val slug: String,
    val fsSlug: String?,
    val name: String,
    val romCount: Int,
    val logoPath: String?,
)

@Entity(tableName = "games", indices = [Index("platformId")])
data class GameEntity(
    @PrimaryKey val id: Long,
    val platformId: Long,
    val platformSlug: String,
    val name: String,
    val sortTitle: String,
    val fsName: String?,
    val fsSizeBytes: Long,
    val coverPath: String?,
    val hasMultipleFiles: Boolean,
)

@Dao
interface LibraryDao {

    @Query("SELECT * FROM platforms WHERE romCount > 0 ORDER BY name")
    fun platforms(): Flow<List<PlatformEntity>>

    @Query("SELECT * FROM games WHERE platformId = :platformId ORDER BY sortTitle")
    fun games(platformId: Long): Flow<List<GameEntity>>

    @Query("SELECT COUNT(*) FROM games")
    suspend fun gameCount(): Int

    @Upsert
    suspend fun upsertPlatforms(platforms: List<PlatformEntity>)

    @Upsert
    suspend fun upsertGames(games: List<GameEntity>)

    @Query("DELETE FROM platforms WHERE id NOT IN (:keep)")
    suspend fun deletePlatformsExcept(keep: List<Long>)

    @Query("DELETE FROM games WHERE platformId = :platformId AND id NOT IN (:keep)")
    suspend fun deleteGamesExcept(platformId: Long, keep: List<Long>)

    @Query("DELETE FROM games WHERE platformId NOT IN (:platformIds)")
    suspend fun deleteGamesOutside(platformIds: List<Long>)

    /** Replaces one platform's games with what the server just listed. */
    @Transaction
    suspend fun replaceGames(platformId: Long, games: List<GameEntity>) {
        upsertGames(games)
        deleteGamesExcept(platformId, games.map { it.id })
    }

    @Query("DELETE FROM platforms")
    suspend fun clearPlatforms()

    @Query("DELETE FROM games")
    suspend fun clearGames()
}

@Database(entities = [PlatformEntity::class, GameEntity::class], version = 1, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
}
