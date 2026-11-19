package app.genkan.ocr

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The part of the top screen to read for a game, as fractions of its width and height. */
@Entity(tableName = "ocr_regions")
data class OcrRegion(
    @PrimaryKey val gameKey: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** Kept inside the screen, corners in order, and big enough to hold a line. */
    fun cleaned(): OcrRegion? {
        val l = minOf(left, right).coerceIn(0f, 1f)
        val r = maxOf(left, right).coerceIn(0f, 1f)
        val t = minOf(top, bottom).coerceIn(0f, 1f)
        val b = maxOf(top, bottom).coerceIn(0f, 1f)
        if (r - l < MIN_SIZE || b - t < MIN_SIZE) return null
        return copy(left = l, top = t, right = r, bottom = b)
    }

    companion object {
        const val MIN_SIZE = 0.03f

        /** Before one is drawn: the lower third, where most games keep their dialogue box. */
        fun default(gameKey: String) = OcrRegion(gameKey, 0.03f, 0.64f, 0.97f, 0.98f)
    }
}

@Dao
interface OcrRegionDao {
    @Query("SELECT * FROM ocr_regions WHERE gameKey = :gameKey")
    fun region(gameKey: String): Flow<OcrRegion?>

    @Upsert
    suspend fun save(region: OcrRegion)

    @Query("DELETE FROM ocr_regions WHERE gameKey = :gameKey")
    suspend fun forget(gameKey: String)
}
