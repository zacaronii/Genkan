package app.genkan.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * What the player does to keep a game at speed, each on unless a debug build's tuning file
 * (files/tuning-switches.json, written by the debug receiver) turns it off, so one can be
 * measured without the others.
 */
data class TuningSwitches(val hints: Boolean = true, val frameRate: Boolean = true, val gameState: Boolean = true) {
    fun toJson(): String = JSONObject().put("hints", hints).put("frame_rate", frameRate).put("game_state", gameState).toString()

    companion object {
        const val FILE = "tuning-switches.json"

        fun read(context: Context, debug: Boolean): TuningSwitches =
            if (!debug) TuningSwitches() else runCatching { parse(File(context.filesDir, FILE).readText()) }.getOrDefault(TuningSwitches())

        fun parse(json: String): TuningSwitches {
            val o = JSONObject(json)
            return TuningSwitches(o.optBoolean("hints", true), o.optBoolean("frame_rate", true), o.optBoolean("game_state", true))
        }
    }
}
