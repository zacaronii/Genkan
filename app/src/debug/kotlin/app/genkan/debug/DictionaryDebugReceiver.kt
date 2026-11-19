package app.genkan.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import app.genkan.dictionary.Dictionaries
import app.genkan.library.LibraryDatabase
import app.genkan.saves.SaveSync
import app.genkan.reader.NowPlaying
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Dictionary checks from adb, logged under the "DictionaryDebug" tag:
 *   adb shell am broadcast -a app.genkan.debug.LOOKUP -n app.genkan/.debug.DictionaryDebugReceiver --es text 眺めた
 *   adb shell am broadcast -a app.genkan.debug.IMPORT_DICTIONARIES -n app.genkan/.debug.DictionaryDebugReceiver [--es folder PATH] [--ez fresh true]
 * (fresh removes every dictionary first, to time a whole import)
 *   adb shell am broadcast -a app.genkan.debug.NOW_PLAYING -n app.genkan/.debug.DictionaryDebugReceiver --el id 1 --es title タイトル [--es platform genkan-pc]
 * (sets what's playing, as launching a game does, so the reader files lines under it)
 *   adb shell am broadcast -a app.genkan.debug.FORGET_GAME -n app.genkan/.debug.DictionaryDebugReceiver [--es game KEY]
 * (deletes one game's backlog, thread choice and reading stats; without a key, logs the keys)
 *   adb shell am broadcast -a app.genkan.debug.SAVE_SYNC -n app.genkan/.debug.DictionaryDebugReceiver --el rom ID [--ez dry true]
 * (syncs one game's save with RomM, logged under "SaveSync"; dry only negotiates and logs the plan;
 * --es copy_to DIR instead downloads RomM's newest Genkan save into DIR, leaving the real one alone)
 */
@AndroidEntryPoint
class DictionaryDebugReceiver : BroadcastReceiver() {

    @Inject lateinit var dictionaries: Dictionaries
    @Inject lateinit var nowPlaying: NowPlaying
    @Inject lateinit var database: LibraryDatabase
    @Inject lateinit var saveSync: SaveSync

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    "app.genkan.debug.LOOKUP" -> lookup(intent.getStringExtra("text").orEmpty())
                    "app.genkan.debug.NOW_PLAYING" -> nowPlaying.started(
                        intent.getLongExtra("id", 0),
                        intent.getStringExtra("title").orEmpty(),
                        intent.getStringExtra("platform"),
                    )
                    "app.genkan.debug.SAVE_SYNC" -> {
                        intent.getStringExtra("copy_to")?.let { dir ->
                            Log.i(TAG, "Save copy: " + saveSync.downloadCopy(intent.getLongExtra("rom", 0), File(dir)))
                            return@launch
                        }
                        val dry = intent.getBooleanExtra("dry", false)
                        val rom = intent.getLongExtra("rom", 0)
                        // Without a rom, every downloaded game.
                        val roms = if (rom != 0L) listOf(rom) else database.downloads().all().first().map { it.romId }
                        roms.forEach { Log.i(TAG, "Save sync of rom $it ran: ${saveSync.sync(it, dryRun = dry)}") }
                    }
                    "app.genkan.debug.FORGET_GAME" -> forgetGame(intent.getStringExtra("game"))
                    "app.genkan.debug.IMPORT_DICTIONARIES" -> {
                        if (intent.getBooleanExtra("fresh", false)) dictionaries.all.first().forEach { dictionaries.remove(it) }
                        dictionaries.importFrom(
                            intent.getStringExtra("folder")?.let(::File)
                                ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        )
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun forgetGame(game: String?) {
        val db = database.openHelper.writableDatabase
        if (game == null) {
            db.query("SELECT gameKey, COUNT(*) FROM reader_lines GROUP BY gameKey UNION ALL SELECT game, COUNT(*) FROM reading_sessions GROUP BY game").use {
                while (it.moveToNext()) Log.i(TAG, "game ${it.getString(0)}: ${it.getInt(1)} rows")
            }
            return
        }
        db.execSQL("DELETE FROM reader_lines WHERE gameKey = ?", arrayOf(game))
        db.execSQL("DELETE FROM hook_threads WHERE gameKey = ?", arrayOf(game))
        db.execSQL("DELETE FROM reading_daily WHERE game = ?", arrayOf(game))
        db.execSQL("DELETE FROM reading_seen WHERE game = ?", arrayOf(game))
        db.execSQL("DELETE FROM reading_sessions WHERE game = ?", arrayOf(game))
        Log.i(TAG, "Forgot $game")
    }

    private suspend fun lookup(text: String) {
        val started = System.nanoTime()
        val result = dictionaries.lookup(text)
        val ms = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "\"$text\": ${result.hits.size} hits in $ms ms, longest match ${result.matchedLength}")
        result.hits.forEach { hit ->
            val freq = hit.frequencies.flatMap { f -> f.values.map { "${f.dictionary} ${it.display.ifEmpty { it.value.toString() }}" } }
            val pitch = hit.pitches.flatMap { p -> p.pitches.map { "${p.dictionary} [${it.position}]" } }
            Log.i(
                TAG,
                "  ${hit.matched} → ${hit.expression}【${hit.reading}】 via ${hit.trace.joinToString(" ") { it.name }.ifEmpty { "-" }}" +
                    "  dicts=${hit.glossaries.map { it.dictionary }.distinct()} freq=$freq pitch=$pitch",
            )
        }
    }

    private companion object {
        const val TAG = "DictionaryDebug"
    }
}
