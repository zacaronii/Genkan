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

/**
 * An emulator whose saves sit in folders shared by every game: [folders] under the first of
 * [bases] (relative to shared storage) that exists. [privileged] when that is another app's
 * Android/data, which only Shizuku can reach.
 */
data class WatchedEmulator(
    val emulator: String,
    val bases: List<String>,
    val folders: List<String>,
    val privileged: Boolean,
) {
    /** The emulator's base folder, or its first candidate when none exists yet. */
    fun base(storage: String, exists: (String) -> Boolean): String =
        bases.map { if (it.isEmpty()) storage else "$storage/$it" }.let { all -> all.firstOrNull { exists("$it/${folders.first().substringBefore('/')}") } ?: all.first() }

    companion object {
        private fun androidData(pkg: String) = listOf("Android/data/$pkg/files")

        /** By RomM platform: the emulator Genkan launches for it and where its saves are. */
        val BY_PLATFORM = mapOf(
            "psx" to WatchedEmulator("duckstation", androidData("com.github.stenzek.duckstation"), listOf("memcards"), privileged = true),
            "ngc" to WatchedEmulator("dolphin", androidData("org.dolphinemu.dolphinemu"), listOf("GC"), privileged = true),
            "wii" to WatchedEmulator("dolphin", androidData("org.dolphinemu.dolphinemu"), listOf("Wii/title"), privileged = true),
            // PPSSPP's memory stick: a folder chosen in PPSSPP, or PSP/ at the root of storage.
            "psp" to WatchedEmulator("ppsspp", listOf("ppsspp", "", "PPSSPP"), listOf("PSP/SAVEDATA"), privileged = false),
        )

        /**
         * The built-in player's, by platform, for a core that keeps its saves in a folder of its own
         * (CoreDef.savesFolder). Dolphin's User folder also holds its config and shader cache: only
         * the memory cards' GC folder is watched, and for a Wii game its NAND's save folder
         * (Wii/title). The files are standalone Dolphin's, under the same paths, so the two share
         * saves through RomM.
         */
        val BUILT_IN = mapOf(
            "ngc" to WatchedEmulator("dolphin", listOf("Genkan/saves/dolphin/User"), listOf("GC"), privileged = false),
            "wii" to WatchedEmulator("dolphin", listOf("Genkan/saves/dolphin/User"), listOf("Wii/title"), privileged = false),
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
