package app.genkan.library

import androidx.room.AutoMigration
import app.genkan.words.AnkiWordDao
import app.genkan.words.AnkiWordEntity
import app.genkan.words.GameLookupDao
import app.genkan.words.GameLookupEntity
import app.genkan.words.LookupCountDao
import app.genkan.words.LookupCountEntity
import app.genkan.words.WordStatusDao
import app.genkan.words.WordStatusEntity
import androidx.room.ColumnInfo
import androidx.room.Dao
import app.genkan.anki.AnkiDao
import app.genkan.anki.MinedWord
import app.genkan.anki.OutboxCard
import app.genkan.dictionary.DictionaryDao
import app.genkan.dictionary.DictionaryEntity
import app.genkan.download.DownloadDao
import app.genkan.saves.SaveFileDao
import app.genkan.saves.SaveFileEntity
import app.genkan.download.DownloadEntity
import app.genkan.ocr.OcrRegion
import app.genkan.ocr.OcrRegionDao
import app.genkan.play.PlaySessionDao
import app.genkan.stats.ReadingDailyEntity
import app.genkan.stats.ReadingDao
import app.genkan.stats.ReadingSeenEntity
import app.genkan.stats.ReadingSessionEntity
import app.genkan.play.PlaySessionEntity
import app.genkan.reader.HookThreadEntity
import app.genkan.reader.ReaderDao
import app.genkan.reader.ReaderLineEntity
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
    /** RomM's metadata, for the bottom screen's details while browsing. */
    @ColumnInfo(defaultValue = "NULL") val summary: String? = null,
    @ColumnInfo(defaultValue = "NULL") val genres: String? = null,
    @ColumnInfo(defaultValue = "NULL") val releaseYear: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val developer: String? = null,
    /**
     * Artwork, for the shelf and the details screen. Every `*Path` here is a server-relative
     * asset path ("/assets/romm/resources/..."), stored already prefixed by
     * [app.genkan.romm.RomMApiFactory.resourcePath], so each one becomes a URL with
     * [app.genkan.romm.RomMApiFactory.assetUrl] exactly as [coverPath] does.
     */
    @ColumnInfo(defaultValue = "NULL") val coverLargePath: String? = null,
    /** The disc or cartridge cut-out, a transparent PNG. */
    @ColumnInfo(defaultValue = "NULL") val physicalPath: String? = null,
    /** The title logo ("wheel"), a transparent PNG. */
    @ColumnInfo(defaultValue = "NULL") val logoPath: String? = null,
    @ColumnInfo(defaultValue = "NULL") val box2dPath: String? = null,
    /** The case's spine. */
    @ColumnInfo(defaultValue = "NULL") val box2dSidePath: String? = null,
    /** The case's back cover. */
    @ColumnInfo(defaultValue = "NULL") val box2dBackPath: String? = null,
    @ColumnInfo(defaultValue = "NULL") val box3dPath: String? = null,
    @ColumnInfo(defaultValue = "NULL") val titleScreenPath: String? = null,
    @ColumnInfo(defaultValue = "NULL") val marqueePath: String? = null,
    @ColumnInfo(defaultValue = "NULL") val fanartPath: String? = null,
    /** Screenshot asset paths, one per line; read them with [screenshotPathList]. */
    @ColumnInfo(defaultValue = "NULL") val screenshotPaths: String? = null,
    /** Set only when the server says the manual exists. */
    @ColumnInfo(defaultValue = "NULL") val manualPath: String? = null,
    /** The lists below are one entry per line; read them with [splitList]. */
    @ColumnInfo(defaultValue = "NULL") val regions: String? = null,
    @ColumnInfo(defaultValue = "NULL") val franchises: String? = null,
    @ColumnInfo(defaultValue = "NULL") val publishers: String? = null,
    @ColumnInfo(defaultValue = "NULL") val gameModes: String? = null,
    /** As the server words it, e.g. "1-2". */
    @ColumnInfo(defaultValue = "NULL") val playerCount: String? = null,
    /** The metadata providers' average rating, out of 100. */
    @ColumnInfo(defaultValue = "NULL") val averageRating: Float? = null,
    /** The first release date, in epoch milliseconds (UTC). */
    @ColumnInfo(defaultValue = "NULL") val releaseDateMillis: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val youtubeVideoId: String? = null,
    /** How many discs the game has, when the server's data says; null for unknown or one. */
    @ColumnInfo(defaultValue = "NULL") val discCount: Int? = null,
    /** HowLongToBeat's times in seconds; null where it has none. */
    @ColumnInfo(defaultValue = "NULL") val hltbId: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val hltbMainStorySec: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val hltbMainPlusExtraSec: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val hltbCompletionistSec: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val hltbAllStylesSec: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val hltbReviewScore: Int? = null,
    /** The signed-in RomM user's own data for the game (`rom_user`). */
    @ColumnInfo(defaultValue = "NULL") val lastPlayedMillis: Long? = null,
    @ColumnInfo(defaultValue = "0") val backlogged: Boolean = false,
    @ColumnInfo(defaultValue = "0") val nowPlaying: Boolean = false,
    @ColumnInfo(defaultValue = "0") val userRating: Int = 0,
    @ColumnInfo(defaultValue = "0") val userDifficulty: Int = 0,
    @ColumnInfo(defaultValue = "0") val userCompletion: Int = 0,
    @ColumnInfo(defaultValue = "NULL") val userStatus: String? = null,
    @ColumnInfo(defaultValue = "0") val userHidden: Boolean = false,
) {
    /** [screenshotPaths] as a list. */
    val screenshotPathList: List<String> get() = splitList(screenshotPaths)

    companion object {
        /** Joins a list for a one-per-line column; an empty or missing list is null. */
        fun joinList(items: List<String>?): String? =
            items?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.joinToString("\n")

        /** Reads a one-per-line column back. */
        fun splitList(column: String?): List<String> =
            column?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
    }
}

@Dao
interface LibraryDao {

    @Query("SELECT * FROM platforms WHERE romCount > 0 ORDER BY name")
    fun platforms(): Flow<List<PlatformEntity>>

    @Query("SELECT * FROM games WHERE platformId = :platformId ORDER BY sortTitle")
    fun games(platformId: Long): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun game(id: Long): GameEntity?

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

@Database(
    entities = [PlatformEntity::class, GameEntity::class, DownloadEntity::class, PlaySessionEntity::class, DictionaryEntity::class,
        ReaderLineEntity::class, HookThreadEntity::class, OcrRegion::class,
        OutboxCard::class, MinedWord::class,
        ReadingSessionEntity::class, ReadingDailyEntity::class, ReadingSeenEntity::class, SaveFileEntity::class,
        WordStatusEntity::class, LookupCountEntity::class, AnkiWordEntity::class, FavouriteEntity::class,
        GameLookupEntity::class],
    version = 18,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10), AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12), AutoMigration(from = 12, to = 13), AutoMigration(from = 13, to = 14), AutoMigration(from = 14, to = 15),
        AutoMigration(from = 15, to = 16), AutoMigration(from = 16, to = 17),
        AutoMigration(from = 17, to = 18)],
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun downloads(): DownloadDao
    abstract fun playSessions(): PlaySessionDao
    abstract fun favourites(): FavouriteDao
    abstract fun gameLookups(): GameLookupDao
    abstract fun dictionaries(): DictionaryDao
    abstract fun reader(): ReaderDao
    abstract fun ocrRegions(): OcrRegionDao
    abstract fun anki(): AnkiDao
    abstract fun reading(): ReadingDao
    abstract fun saveFiles(): SaveFileDao
    abstract fun wordStatuses(): WordStatusDao
    abstract fun lookupCounts(): LookupCountDao
    abstract fun ankiWords(): AnkiWordDao
}
