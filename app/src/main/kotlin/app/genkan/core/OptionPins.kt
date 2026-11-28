package app.genkan.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The core options set by the owner (files/tuning/options.json): for a console, by platform, and for
 * a game, by RomM rom id, each a key → value of its core's ([CoreOptions]). A game's wins over its
 * console's, and both over the ladder's step and the core's defaults ([OptionPins.applied]). Read
 * from the file each time, so the player's own process reads what Settings last wrote.
 */
@Singleton
class OptionPins internal constructor(private val dir: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "tuning"))

    private val file get() = File(dir, FILE)

    /** Counts this process's changes, for screens showing pins to read them again. */
    val changes = MutableStateFlow(0)

    @Synchronized
    fun console(platform: String): Map<String, String> = scope(read(), "console:$platform")

    @Synchronized
    fun game(romId: Long): Map<String, String> = scope(read(), "game:$romId")

    /** Pins [key] to [value] for [platform]'s games; null unpins it. */
    fun setConsole(platform: String, key: String, value: String?) = set("console:$platform", key, value)

    /** Pins [key] to [value] for one game; null unpins it. */
    fun setGame(romId: Long, key: String, value: String?) = set("game:$romId", key, value)

    /** Every game with pins of its own, by RomM rom id. */
    @Synchronized
    fun games(): Map<Long, Map<String, String>> {
        val all = read()
        return all.keys().asSequence().mapNotNull { key ->
            key.removePrefix("game:").takeIf { key.startsWith("game:") }?.toLongOrNull()?.let { it to scope(all, key) }
        }.filter { it.second.isNotEmpty() }.toMap()
    }

    /** Clears every pin of one game: it runs as its console does again. */
    @Synchronized
    fun clearGame(romId: Long) {
        val all = read()
        if (all.remove("game:$romId") != null) write(all)
        changes.value++
    }

    @Synchronized
    private fun set(scope: String, key: String, value: String?) {
        val all = read()
        val pins = all.optJSONObject(scope) ?: JSONObject()
        if (value == null) pins.remove(key) else pins.put(key, value)
        if (pins.length() == 0) all.remove(scope) else all.put(scope, pins)
        write(all)
        changes.value++
    }

    private fun scope(all: JSONObject, scope: String): Map<String, String> =
        all.optJSONObject(scope)?.let { pins -> pins.keys().asSequence().associateWith { pins.optString(it) }.filterValues { it.isNotBlank() } } ?: emptyMap()

    private fun read(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    private fun write(all: JSONObject) {
        dir.mkdirs()
        // Whole or not at all, as TuningStore's.
        val part = File(dir, "$FILE.part")
        part.writeText(all.toString())
        part.renameTo(file)
    }

    companion object {
        const val FILE = "options.json"

        /** The pins a game starts with: its console's, then its own over them. */
        fun applied(console: Map<String, String>, game: Map<String, String>): Map<String, String> = console + game

        /** [pins] for the player's start intent. */
        fun encode(pins: Map<String, String>): String = JSONObject(pins).toString()

        /** What [encode] made; anything else is none. */
        fun decode(text: String?): Map<String, String> = runCatching {
            JSONObject(text ?: return emptyMap()).let { j -> j.keys().asSequence().associateWith { j.getString(it) } }
        }.getOrDefault(emptyMap())
    }
}
