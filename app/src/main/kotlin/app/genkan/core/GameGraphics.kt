package app.genkan.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Each game's own graphics (files/tuning/graphics.json, by RomM rom id): one of its core's
 * renderers ([TuningLadder.renderers]), chosen on the game's details. It wins over the game's step
 * (learned, set or the catalog's) and its core's default; none is Automatic, the ladder as before.
 * Vulkan chosen still falls back to GL where Vulkan can't run.
 */
@Singleton
class GameGraphics internal constructor(private val dir: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "tuning"))

    private val file get() = File(dir, FILE)

    /** Counts this process's changes, for screens showing a choice to read it again. */
    val changes = MutableStateFlow(0)

    /** The renderer step chosen for RomM rom [romId], or null for Automatic. */
    @Synchronized
    fun choice(romId: Long): String? = read().optString(romId.toString()).takeIf { it.isNotBlank() }

    /** Chooses [step] for [romId]; null goes back to Automatic. */
    @Synchronized
    fun set(romId: Long, step: String?) {
        val all = read()
        if (step == null) all.remove(romId.toString()) else all.put(romId.toString(), step)
        write(all)
        changes.value++
    }

    /** Every game with graphics of its own, by RomM rom id. */
    @Synchronized
    fun all(): Map<Long, String> {
        val all = read()
        return all.keys().asSequence().mapNotNull { key ->
            val id = key.toLongOrNull() ?: return@mapNotNull null
            all.optString(key).takeIf { it.isNotBlank() }?.let { id to it }
        }.toMap()
    }

    private fun read(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    private fun write(all: JSONObject) {
        dir.mkdirs()
        // Whole or not at all, as TuningStore's.
        val part = File(dir, "$FILE.part")
        part.writeText(all.toString())
        part.renameTo(file)
    }

    companion object {
        const val FILE = "graphics.json"

        /** The choice after [current] on [ladder]: Automatic, then each renderer in turn, then Automatic again. */
        fun next(ladder: TuningLadder, current: String?): String? {
            val order = listOf(null) + ladder.renderers
            val at = order.indexOf(ladder.step(current)?.id?.takeIf { it in ladder.renderers })
            return order[(at + 1) % order.size]
        }
    }
}
