package app.genkan.debug

import android.content.BroadcastReceiver
import android.content.Context
import app.genkan.azahar.AzaharText
import android.content.Intent
import android.os.Environment
import android.util.Log
import app.genkan.dictionary.Dictionaries
import app.genkan.download.DownloadQueue
import app.genkan.audio.GameAudioRecorder
import app.genkan.capture.CaptureConsentActivity
import app.genkan.capture.ScreenCapture
import app.genkan.core.BuiltInPlayer
import app.genkan.core.BuiltInText
import app.genkan.core.PlayerSettings
import app.genkan.launch.GameLauncher
import app.genkan.launch.topScreenOptions
import app.genkan.ocr.OcrReader
import app.genkan.launch.LaunchResult
import app.genkan.library.LibraryDatabase
import app.genkan.saves.SaveSync
import app.genkan.privileged.Privileged
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
 *   adb shell am broadcast -a app.genkan.debug.SHIZUKU_PROBE -n app.genkan/.debug.DictionaryDebugReceiver
 * (writes, lists and deletes a probe file in DuckStation's memory card folder through Shizuku)
 *   adb shell am broadcast -a app.genkan.debug.AZAHAR_FIND -n app.genkan/.debug.DictionaryDebugReceiver --es text 漢字
 * (looks for on-screen text in the running 3DS game's memory, through Azahar's RPC)
 *   adb shell am broadcast -a app.genkan.debug.AZAHAR_OFFER -n app.genkan/.debug.DictionaryDebugReceiver --es text 漢字
 * (hands the line to the 3DS text source as if OCR had read it: it locks on and follows it)
 *   adb shell am broadcast -a app.genkan.debug.AZAHAR_POINTERS -n app.genkan/.debug.DictionaryDebugReceiver --es from 089ddc90 --es to 089e0000
 * (logs every pointer in the 3DS game's memory into the range, in hex)
 *   adb shell am broadcast -a app.genkan.debug.AZAHAR_VALUES -n app.genkan/.debug.DictionaryDebugReceiver --es from 200 --es to 4000
 * (logs every aligned word holding a value in the range, for finding offsets that move)
 *   adb shell am broadcast -a app.genkan.debug.OCR -n app.genkan/.debug.DictionaryDebugReceiver [--ez on false]
 * (switches OCR on, asking for screen capture on the top screen when it isn't running, or off)
 *   adb shell am broadcast -a app.genkan.debug.AUDIO_CLIP -n app.genkan/.debug.DictionaryDebugReceiver [--el ms 3000]
 * (cuts the last seconds of game audio as a card would; GameAudioRecorder logs its level)
 *   adb shell am broadcast -a app.genkan.debug.PLAYER -n app.genkan/.debug.DictionaryDebugReceiver [--ez on false]
 * (switches the built-in player setting)
 *   adb shell am broadcast -a app.genkan.debug.PLAYER_FIND -n app.genkan/.debug.DictionaryDebugReceiver --es text 漢字
 * (looks for on-screen text in the built-in player's game memory)
 *   adb shell am broadcast -a app.genkan.debug.PLAYER_OFFER -n app.genkan/.debug.DictionaryDebugReceiver --es text 漢字
 * (hands the line to the built-in player's text source as if OCR had read it)
 *   adb shell am broadcast -a app.genkan.debug.PLAYER_REGIONS -n app.genkan/.debug.DictionaryDebugReceiver
 * (logs the memory regions the built-in player's core describes)
 *   adb shell am broadcast -a app.genkan.debug.DOWNLOAD -n app.genkan/.debug.DictionaryDebugReceiver --el rom ID
 * (downloads one game from the library, as choosing it in the grid does)
 *   adb shell am broadcast -a app.genkan.debug.LAUNCH -n app.genkan/.debug.DictionaryDebugReceiver --el rom ID
 * (starts a downloaded game and marks it as playing, without the save sync the grid runs first)
 */
@AndroidEntryPoint
class DictionaryDebugReceiver : BroadcastReceiver() {

    @Inject lateinit var dictionaries: Dictionaries
    @Inject lateinit var nowPlaying: NowPlaying
    @Inject lateinit var database: LibraryDatabase
    @Inject lateinit var saveSync: SaveSync
    @Inject lateinit var privileged: Privileged
    @Inject lateinit var azahar: AzaharText
    @Inject lateinit var downloadQueue: DownloadQueue
    @Inject lateinit var gameLauncher: GameLauncher
    @Inject lateinit var ocr: OcrReader
    @Inject lateinit var capture: ScreenCapture
    @Inject lateinit var builtInText: BuiltInText
    @Inject lateinit var player: BuiltInPlayer
    @Inject lateinit var playerSettings: PlayerSettings
    @Inject lateinit var audio: GameAudioRecorder

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
                    "app.genkan.debug.DOWNLOAD" -> {
                        val game = database.library().game(intent.getLongExtra("rom", 0))
                        if (game == null) Log.w(TAG, "No such game") else downloadQueue.enqueue(game).join()
                    }
                    "app.genkan.debug.LAUNCH" -> {
                        val rom = intent.getLongExtra("rom", 0)
                        val game = database.library().game(rom)
                        val download = database.downloads().get(rom)
                        if (game == null || download == null) {
                            Log.w(TAG, "Not downloaded")
                        } else {
                            val result = gameLauncher.launch(game, download)
                            Log.i(TAG, "Launch: $result")
                            if (result is LaunchResult.Launched) nowPlaying.started(game.id, game.name, game.platformSlug)
                        }
                    }
                    "app.genkan.debug.AZAHAR_FIND" -> {
                        val started = System.currentTimeMillis()
                        val found = azahar.searchNow(intent.getStringExtra("text").orEmpty())
                        Log.i(TAG, "Azahar search: ${found.size} strings in ${System.currentTimeMillis() - started} ms")
                        found.take(20).forEach { Log.i(TAG, "  0x%08x %s: %s".format(it.start, it.hit.charset.name(), it.text.take(1500))) }
                    }
                    "app.genkan.debug.AZAHAR_POINTERS" -> {
                        val from = intent.getStringExtra("from").orEmpty().toLong(16)
                        val to = intent.getStringExtra("to").orEmpty().toLong(16)
                        azahar.pointersNow(from..to).forEach { (slot, value) -> Log.i(TAG, "  pointer 0x%08x -> 0x%08x".format(slot, value)) }
                    }
                    "app.genkan.debug.AZAHAR_VALUES" -> {
                        val from = intent.getStringExtra("from").orEmpty().toLong(16)
                        val to = intent.getStringExtra("to").orEmpty().toLong(16)
                        azahar.valuesNow(from..to).forEach { (slot, value) -> Log.i(TAG, "  value 0x%08x = 0x%08x".format(slot, value)) }
                    }
                    "app.genkan.debug.OCR" -> {
                        val on = intent.getBooleanExtra("on", true)
                        ocr.setEnabled(on)
                        if (on && !capture.running.value) {
                            context.startActivity(
                                Intent(context, CaptureConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                topScreenOptions(),
                            )
                        }
                    }
                    "app.genkan.debug.AZAHAR_OFFER" -> azahar.offered.emit(intent.getStringExtra("text").orEmpty())
                    "app.genkan.debug.AUDIO_CLIP" -> {
                        val now = System.currentTimeMillis()
                        audio.clip(now - intent.getLongExtra("ms", 3000), now)
                    }
                    "app.genkan.debug.PLAYER" -> playerSettings.setEnabled(intent.getBooleanExtra("on", true))
                    "app.genkan.debug.PLAYER_FIND" -> {
                        val started = System.currentTimeMillis()
                        val found = builtInText.searchNow(intent.getStringExtra("text").orEmpty())
                        Log.i(TAG, "Player search: ${found.size} strings in ${System.currentTimeMillis() - started} ms")
                        found.take(20).forEach { Log.i(TAG, "  0x%08x %s: %s".format(it.start, it.hit.charset.name(), it.text.take(300))) }
                    }
                    "app.genkan.debug.PLAYER_OFFER" -> builtInText.offered.emit(intent.getStringExtra("text").orEmpty())
                    "app.genkan.debug.PLAYER_REGIONS" -> player.memory()?.regions.orEmpty().forEach {
                        Log.i(TAG, "  region 0x%08x-0x%08x".format(it.first, it.last))
                    }
                    "app.genkan.debug.SHIZUKU_PROBE" -> {
                        val files = privileged.files()
                        Log.i(TAG, "Shizuku ${privileged.state()}, helper ${files != null}")
                        if (files != null) {
                            val storage = Environment.getExternalStorageDirectory().path
                            val cards = "$storage/Android/data/com.github.stenzek.duckstation/files/memcards"
                            val probe = File(storage, "Genkan/.staging/probe.txt").apply { parentFile?.mkdirs(); writeText("genkan") }
                            Log.i(TAG, "write ${files.copy(probe.path, "$cards/genkan-probe.txt")}, list ${files.list(cards).toList()}")
                            val back = File(storage, "Genkan/.staging/probe-back.txt")
                            Log.i(TAG, "read ${files.copy("$cards/genkan-probe.txt", back.path)} -> ${back.readText()}")
                            Log.i(TAG, "delete ${files.deleteRecursively("$cards/genkan-probe.txt")}, list ${files.list(cards).toList()}")
                            probe.delete(); back.delete()
                        }
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
