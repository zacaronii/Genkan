package app.genkan.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's own step for a game (files/tuning/overrides.json, by RomM rom id), over the
 * catalog's: learning sets it, and Reset or a debug build clears it. Each says when it was set
 * and why, for the game's details.
 */
@Singleton
class TuningStore internal constructor(private val dir: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "tuning"))

    data class Override(val step: String, val atMs: Long, val why: String)

    private val file get() = File(dir, FILE)

    /** Counts this process's changes, for screens showing a step to read it again (the player writes from its own process). */
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

    @Synchronized
    fun override(romId: Long): Override? = read().optJSONObject(romId.toString())?.let {
        Override(it.optString("step"), it.optLong("at"), it.optString("why"))
    }?.takeIf { it.step.isNotBlank() }

    @Synchronized
    fun setOverride(romId: Long, step: String, why: String, atMs: Long = System.currentTimeMillis()) {
        write(read().put(romId.toString(), JSONObject().put("step", step).put("at", atMs).put("why", why)))
        changes.value++
    }

    /** Every game with a step of its own, by RomM rom id. */
    @Synchronized
    fun all(): Map<Long, Override> {
        val all = read()
        return all.keys().asSequence().mapNotNull { key ->
            val id = key.toLongOrNull() ?: return@mapNotNull null
            override(id)?.let { id to it }
        }.toMap()
    }

    /** Clears every game's step (Settings › Advanced › Tuning steps › Clear all). */
    @Synchronized
    fun clearAll() {
        write(JSONObject())
        changes.value++
    }

    @Synchronized
    fun clearOverride(romId: Long) {
        val all = read()
        if (all.remove(romId.toString()) != null) write(all)
        changes.value++
    }

    private fun read(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    private fun write(all: JSONObject) {
        dir.mkdirs()
        // Whole or not at all: a half-written file would lose every game's step.
        val part = File(dir, "$FILE.part")
        part.writeText(all.toString())
        part.renameTo(file)
    }

    companion object {
        const val FILE = "overrides.json"
    }
}
