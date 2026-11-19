package app.genkan.saves

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * A file in an emulator's shared save folder (a DuckStation memory card, a Dolphin GCI) known to
 * belong to one game: learned from what changed there while that game ran, or from a restore.
 */
@Entity(tableName = "save_files", primaryKeys = ["emulator", "path"])
data class SaveFileEntity(val emulator: String, val path: String, val romId: Long)

@Dao
interface SaveFileDao {
    @Query("SELECT path FROM save_files WHERE romId = :romId AND emulator = :emulator ORDER BY path")
    suspend fun paths(romId: Long, emulator: String): List<String>

    /** A file claimed by one game is never handed to another. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claim(files: List<SaveFileEntity>)
}

/** An emulator whose saves sit in folders shared by every game, inside its own Android/data. */
data class WatchedEmulator(val emulator: String, val packageName: String, val folders: List<String>) {

    /** The real folders, under the emulator's Android/data/<package>/files. */
    fun roots(storage: String): List<String> = folders.map { "$storage/Android/data/$packageName/files/$it" }

    companion object {
        /** By RomM platform: the emulator Genkan launches for it and where its saves are. */
        val BY_PLATFORM = mapOf(
            "psx" to WatchedEmulator("duckstation", "com.github.stenzek.duckstation", listOf("memcards")),
            "ngc" to WatchedEmulator("dolphin", "org.dolphinemu.dolphinemu", listOf("GC")),
            "wii" to WatchedEmulator("dolphin", "org.dolphinemu.dolphinemu", listOf("Wii/title")),
        )
    }
}

/** Which files a session changed: new ones, and ones whose size or time moved. */
object SaveSnapshot {
    data class Entry(val path: String, val size: Long, val modified: Long)

    fun parse(lines: Array<String>): Map<String, Entry> = lines.mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size != 3) return@mapNotNull null
        Entry(parts[0], parts[1].toLongOrNull() ?: return@mapNotNull null, parts[2].toLongOrNull() ?: return@mapNotNull null)
    }.associateBy { it.path }

    fun changed(before: Map<String, Entry>, after: Map<String, Entry>): List<String> =
        after.values.filter { now -> before[now.path].let { it == null || it.size != now.size || it.modified != now.modified } }
            .map { it.path }.sorted()
}
