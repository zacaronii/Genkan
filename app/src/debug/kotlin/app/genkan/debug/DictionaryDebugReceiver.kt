package app.genkan.debug

import android.content.BroadcastReceiver
import android.content.Context
import app.genkan.azahar.AzaharText
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import app.genkan.dictionary.Dictionaries
import app.genkan.download.DownloadQueue
import app.genkan.audio.AacEncoder
import app.genkan.audio.GameAudioRecorder
import app.genkan.capture.CaptureConsentActivity
import app.genkan.capture.ScreenCapture
import app.genkan.core.BuiltInPlayer
import app.genkan.core.BuiltInText
import app.genkan.core.CoreActivity
import app.genkan.core.OverlayPanel
import app.genkan.core.PlayerSettings
import app.genkan.core.ScreenLook
import app.genkan.launch.GameLauncher
import app.genkan.launch.topScreenOptions
import app.genkan.ocr.OcrReader
import app.genkan.launch.LaunchResult
import app.genkan.library.LibraryDatabase
import app.genkan.saves.SaveSync
import app.genkan.stats.ReadingLogConfig
import app.genkan.stats.ReadingLogSettings
import app.genkan.privileged.Privileged
import app.genkan.reader.NowPlaying
import app.genkan.reader.HookMessage
import app.genkan.reader.ReaderSession
import app.genkan.ui.FullBoxShelf
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 *   adb shell am broadcast -a app.genkan.debug.SCAN -n app.genkan/.debug.DictionaryDebugReceiver --es text 一行
 *     logs the words a line's marks would cover.
 *   adb shell am broadcast -a app.genkan.debug.LAYOUT -n app.genkan/.debug.DictionaryDebugReceiver --es json '{...}'
 *     has the built-in player watch a layout given here (with the catalog entry's table), as if the entry had it.
 *   adb shell am broadcast -a app.genkan.debug.LINE -n app.genkan/.debug.DictionaryDebugReceiver --es text 一行
 *     puts a line in the reader's backlog, as a texthooker would ([--es speaker 名前] says who).
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
 *   adb shell am broadcast -a app.genkan.debug.CARD_MEDIA -n app.genkan/.debug.DictionaryDebugReceiver
 * (writes what a card made now would carry, the frame as WebP and the last 3 s as M4A, to Download/genkan-card-media)
 *   adb shell am broadcast -a app.genkan.debug.PLAYER -n app.genkan/.debug.DictionaryDebugReceiver [--ez on false]
 * (switches the built-in player setting)
 *   adb shell am broadcast -a app.genkan.debug.FULL_BOX -n app.genkan/.debug.DictionaryDebugReceiver --ei angle 28
 * (turns the shelf's full box that many degrees, to try other views; past 90 shows the back. Lasts until the app
 *  restarts. On or off is the Full box setting)
 *   adb shell am broadcast -a app.genkan.debug.MANUAL_OCR -n app.genkan/.debug.DictionaryDebugReceiver --es file cache/manuals/<name>.pdf [--ei page 0] [--ei width 1240]
 * (times one manual page read as a tap would: render, then ML Kit; logs sizes and timings at info, never the text)
 *   adb shell am broadcast -a app.genkan.debug.PANEL -n app.genkan/.debug.DictionaryDebugReceiver --es items 'Yes|No' [--ei cursor 0]
 * (draws a choice panel with those rows over the built-in player's game, as a layout's choice would; no items clears it)
 *   adb shell am broadcast -a app.genkan.debug.LOOK -n app.genkan/.debug.DictionaryDebugReceiver --es look CRT
 * (draws the built-in player's game with that ScreenLook for this play, as the reader's Look chip does)
 *   adb shell am broadcast -a app.genkan.debug.PLAYER_FIND -n app.genkan/.debug.DictionaryDebugReceiver --es text 漢字
 * (looks for on-screen text in the built-in player's game memory)
 *   adb shell am broadcast -a app.genkan.debug.PLAYER_OFFER -n app.genkan/.debug.DictionaryDebugReceiver --es text 漢字
 * (hands the line to the built-in player's text source as if OCR had read it)
 *   adb shell am broadcast -a app.genkan.debug.PLAYER_REGIONS -n app.genkan/.debug.DictionaryDebugReceiver
 * (logs the memory regions the built-in player's core describes)
 *   adb shell am broadcast -a app.genkan.debug.PLAYER_RAM -n app.genkan/.debug.DictionaryDebugReceiver --es name line1
 * (writes each region of the built-in player's game memory, as it is, to files/debug-ram/<name>-<start>.bin, to search
 *  off the device; it holds script text, so it goes nowhere else and is deleted after)
 *   adb shell am broadcast -a app.genkan.debug.SCRIPT_RUN -n app.genkan/.debug.DictionaryDebugReceiver --es path /sdcard/Download/x.lua [--es data tbl=/sdcard/Download/x.tbl]
 * (runs a Yomikata script in the built-in player for the game running, its events logged under CoreActivity;
 *  --ez stop true stops it)
 *   adb shell am broadcast -a app.genkan.debug.PLAYER_FRAMES -n app.genkan/.debug.DictionaryDebugReceiver
 * (writes the built-in player's frame as a snapshot takes it, read on its own and with a moment's memory, to
 *  files/debug-ram/frame-{screen,moment}.png; the memory isn't kept)
 *   adb shell am broadcast -a app.genkan.debug.PLAYER_STATE_SIZE -n app.genkan/.debug.DictionaryDebugReceiver
 * (logs how large the built-in player's savestate is, unpacked and gzipped, as a snapshot would take it; the state is
 *  deleted at once)
 *   adb shell am broadcast -a app.genkan.debug.DOWNLOAD -n app.genkan/.debug.DictionaryDebugReceiver --el rom ID
 * (downloads one game from the library, as choosing it in the grid does)
 *   adb shell am broadcast -a app.genkan.debug.LAUNCH -n app.genkan/.debug.DictionaryDebugReceiver --el rom ID
 * (starts a downloaded game and marks it as playing, without the save sync the grid runs first)
 *   adb shell am broadcast -a app.genkan.debug.PLAYER_FILE -n app.genkan/.debug.DictionaryDebugReceiver --es path /sdcard/x.nds --es platform nds
 * (plays a file that isn't in the library, such as homebrew, in the built-in player)
 *   adb shell am broadcast -a app.genkan.debug.HEADPHONES_SKIP_SHIZUKU -n app.genkan/.debug.DictionaryDebugReceiver --ez skip true
 * (a tap on headphones goes on as if Shizuku couldn't connect them, without stopping Shizuku; false undoes it)
 */
@AndroidEntryPoint
class DictionaryDebugReceiver : BroadcastReceiver() {

    @Inject lateinit var dictionaries: Dictionaries
    @Inject lateinit var nowPlaying: NowPlaying
    @Inject lateinit var database: LibraryDatabase
    @Inject lateinit var saveSync: SaveSync
    @Inject lateinit var privileged: Privileged
    @Inject lateinit var mlKitOcr: app.genkan.ocr.MlKitJapaneseOcr
    @Inject lateinit var azahar: AzaharText
    @Inject lateinit var downloadQueue: DownloadQueue
    @Inject lateinit var gameLauncher: GameLauncher
    @Inject lateinit var ocr: OcrReader
    @Inject lateinit var capture: ScreenCapture
    @Inject lateinit var builtInText: BuiltInText
    @Inject lateinit var player: BuiltInPlayer
    @Inject lateinit var playerSettings: PlayerSettings
    @Inject lateinit var audio: GameAudioRecorder
    @Inject lateinit var readingLog: ReadingLogSettings
    @Inject lateinit var readerSession: ReaderSession
    @Inject lateinit var headphones: app.genkan.bluetooth.Headphones

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
                    "app.genkan.debug.SCAN" -> {
                        val text = intent.getStringExtra("text").orEmpty()
                        val started = System.nanoTime()
                        val words = dictionaries.scan(text)
                        Log.i(TAG, "Scan in ${(System.nanoTime() - started) / 1_000_000} ms: " + words.joinToString(" ") { "${text.substring(it.start, it.end)}→${it.expression}【${it.reading}】" })
                    }
                    "app.genkan.debug.LAYOUT" -> builtInText.debugWatch(intent.getStringExtra("json").orEmpty())
                    "app.genkan.debug.LINE" -> readerSession.submit(
                        // With a speaker it's a whole line, as a layout's: only those carry one.
                        HookMessage(intent.getStringExtra("text").orEmpty(), whole = intent.hasExtra("speaker"), speaker = intent.getStringExtra("speaker")),
                    )
                    "app.genkan.debug.AUDIO_CLIP" -> {
                        val now = System.currentTimeMillis()
                        audio.clip(now - intent.getLongExtra("ms", 3000), now)
                    }
                    "app.genkan.debug.CARD_MEDIA" -> {
                        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "genkan-card-media").apply { mkdirs() }
                        val frame = capture.frame()
                        frame?.let { bmp -> File(dir, "picture.webp").outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) } }
                        val now = System.currentTimeMillis()
                        val voice = audio.clip(now - 3000, now)
                        voice?.let { AacEncoder.encode(it, GameAudioRecorder.SAMPLE_RATE, File(dir, "audio.m4a")) }
                        Log.i(TAG, "Card media: frame ${frame?.width}x${frame?.height}, audio ${voice?.size} samples, in $dir")
                    }
                    "app.genkan.debug.PLAYER" -> playerSettings.setEnabled(intent.getBooleanExtra("on", true))
                    "app.genkan.debug.FULL_BOX" -> FullBoxShelf.angle.value = intent.getIntExtra("angle", 28).toFloat()
                    "app.genkan.debug.MANUAL_OCR" -> {
                        // Times reading one manual page as a tap would: a render at the given width, then ML Kit.
                        // Logs numbers only, never the text; nothing is looked up.
                        val file = File(context.dataDir, intent.getStringExtra("file").orEmpty())
                        val page = intent.getIntExtra("page", 0)
                        val width = intent.getIntExtra("width", 1240)
                        val pages = app.genkan.ui.PdfManualPages.open(file)
                        try {
                            val runtime = Runtime.getRuntime()
                            val nativeBefore = android.os.Debug.getNativeHeapAllocatedSize()
                            val t0 = System.nanoTime()
                            val bitmap = pages.render(page, width)
                            val t1 = System.nanoTime()
                            val lines = bitmap?.let { mlKitOcr.read(it) }.orEmpty()
                            val t2 = System.nanoTime()
                            val nativePeak = android.os.Debug.getNativeHeapAllocatedSize()
                            Log.i(
                                TAG,
                                "Manual OCR: page ${page + 1} ${bitmap?.width}x${bitmap?.height} (${(bitmap?.allocationByteCount ?: 0) / 1024} KB), " +
                                    "render ${(t1 - t0) / 1_000_000} ms, read ${(t2 - t1) / 1_000_000} ms, ${lines.size} lines, " +
                                    "${lines.sumOf { it.text.length }} chars, native heap +${(nativePeak - nativeBefore) / 1024} KB, " +
                                    "java heap ${(runtime.totalMemory() - runtime.freeMemory()) / 1024} KB",
                            )
                            bitmap?.recycle()
                        } finally {
                            pages.close()
                        }
                    }
                    "app.genkan.debug.PANEL" -> {
                        val items = intent.getStringExtra("items").orEmpty().split('|').filter { it.isNotBlank() }
                        val cursor = intent.getIntExtra("cursor", -1).takeIf { it >= 0 }
                        val panel = items.takeIf { it.isNotEmpty() }?.let { OverlayPanel(OverlayPanel.Kind.CHOICE, it, cursor) }
                        player.game.value?.session?.showPanel(panel?.toJson().orEmpty())
                    }
                    "app.genkan.debug.LOOK" -> player.setLook(ScreenLook.of(intent.getStringExtra("look")))
                    // Test play without sending sessions: the real settings wait in a file until resumed.
                    "app.genkan.debug.READING_LOG_PAUSE" -> {
                        val kept = File(context.filesDir, "debug-reading-log")
                        if (intent.getBooleanExtra("paused", true)) {
                            if (!kept.exists()) {
                                val config = readingLog.config.first()
                                kept.writeText(config.address + "\n" + config.token)
                                readingLog.save(ReadingLogConfig())
                            }
                        } else if (kept.exists()) {
                            val (address, token) = kept.readText().split("\n", limit = 2)
                            readingLog.save(ReadingLogConfig(address, token))
                            kept.delete()
                        }
                        Log.i(TAG, "Reading log paused: ${kept.exists()}")
                    }
                    "app.genkan.debug.PLAYER_FIND" -> {
                        val started = System.currentTimeMillis()
                        val found = builtInText.searchNow(intent.getStringExtra("text").orEmpty())
                        Log.i(TAG, "Player search: ${found.size} strings in ${System.currentTimeMillis() - started} ms")
                        found.take(20).forEach { Log.i(TAG, "  0x%08x %s: %s".format(it.start, it.hit.charset.name(), it.text.take(300))) }
                    }
                    "app.genkan.debug.PLAYER_OFFER" -> builtInText.offered.emit(intent.getStringExtra("text").orEmpty())
                    "app.genkan.debug.HEADPHONES_SKIP_SHIZUKU" -> {
                        headphones.skipShizuku = intent.getBooleanExtra("skip", true)
                        Log.i(TAG, "Headphones: Shizuku's connect ${if (headphones.skipShizuku) "skipped" else "used"}")
                    }
                    "app.genkan.debug.PLAYER_FILE" -> {
                        val start = Intent(context, CoreActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(CoreActivity.EXTRA_ROM, intent.getStringExtra("path"))
                            .putExtra(CoreActivity.EXTRA_PLATFORM, intent.getStringExtra("platform"))
                            .putExtra(CoreActivity.EXTRA_LOW_LATENCY, intent.getBooleanExtra("low_latency", true))
                        withContext(Dispatchers.Main) { context.startActivity(start, topScreenOptions()) }
                    }
                    "app.genkan.debug.PLAYER_DUMP" -> player.memory()?.use { memory ->
                        val bytes = memory.read(intent.getLongExtra("address", 0), intent.getIntExtra("size", 4096))
                        val out = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "genkan-dump.bin")
                        bytes?.let(out::writeBytes)
                        Log.i(TAG, "Dumped ${bytes?.size} bytes to $out")
                    }
                    "app.genkan.debug.PLAYER_REGIONS" -> player.memory()?.regions.orEmpty().forEach {
                        Log.i(TAG, "  region 0x%08x-0x%08x".format(it.first, it.last))
                    }
                    "app.genkan.debug.SCRIPT_RUN" -> {
                        if (intent.getBooleanExtra("stop", false)) {
                            player.unloadScript()
                            Log.i(TAG, "Script stopped")
                        } else {
                            val file = File(intent.getStringExtra("path").orEmpty())
                            val data = intent.getStringExtra("data").orEmpty().split(',').filter { '=' in it }
                                .associate { it.substringBefore('=') to File(it.substringAfter('=')).readBytes() }
                            val platform = player.game.value?.core?.platforms?.first()
                            val ram = player.memory()?.use { app.genkan.memory.RamSnapshots.mainRamOf(it, platform) }
                            val gameId = ram?.let { app.genkan.memory.RamSnapshots.gameId(platform, it) } ?: file.nameWithoutExtension
                            val buildId = ram?.takeIf { platform == "ngc" }?.let { app.genkan.memory.RamSnapshots.gcBuildId(it) }
                            val error = player.loadScript(file.name, file.readBytes(), data, gameId, buildId)
                            Log.i(TAG, "Script ${file.name} for $gameId ($buildId): ${error ?: "loaded"}")
                        }
                    }
                    "app.genkan.debug.PLAYER_FRAMES" -> {
                        val dir = File(context.filesDir, "debug-ram").apply { mkdirs() }
                        fun save(name: String, frame: android.graphics.Bitmap?) {
                            val out = File(dir, "frame-$name.png")
                            frame?.let { f -> out.outputStream().use { f.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                            Log.i(TAG, "Frame $name: ${frame?.let { "${it.width}x${it.height}" } ?: "none"}")
                        }
                        save("screen", player.frame())
                        val ram = player.game.value?.core?.platforms?.firstNotNullOfOrNull { app.genkan.memory.MainRam.of(it) }
                        val scratch = File(context.filesDir, "yomikata-snapshots/frames.capture").apply { parentFile?.mkdirs() }
                        save("moment", ram?.let { player.moment(it.range, scratch)?.frame })
                    }
                    "app.genkan.debug.PLAYER_STATE_SIZE" -> {
                        val file = File(context.filesDir, "yomikata-snapshots/state-size.state.gz").apply { parentFile?.mkdirs() }
                        val started = System.currentTimeMillis()
                        val size = player.saveState(file)
                        Log.i(TAG, "Savestate: ${size ?: "none"} bytes unpacked, ${file.length()} gzipped, in ${System.currentTimeMillis() - started} ms")
                        file.delete()
                    }
                    "app.genkan.debug.PLAYER_RAM" -> {
                        val memory = player.memory()
                        val name = intent.getStringExtra("name").orEmpty().filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "dump" }
                        val dir = File(context.filesDir, "debug-ram").apply { mkdirs() }
                        val started = System.currentTimeMillis()
                        memory?.regions.orEmpty().forEach { region ->
                            val file = File(dir, "%s-%08x.bin".format(name, region.first))
                            var short = 0
                            file.outputStream().buffered().use { out ->
                                var at = region.first
                                while (at <= region.last) {
                                    val n = minOf(memory!!.maxRead.toLong(), region.last - at + 1).toInt()
                                    // An unreadable stretch stays zeros, so offsets in the file are the region's.
                                    out.write(memory.read(at, n) ?: ByteArray(n).also { short++ })
                                    at += n
                                }
                            }
                            Log.i(TAG, "Dumped 0x%08x-0x%08x to %s, %d unreadable chunks".format(region.first, region.last, file.name, short))
                        }
                        Log.i(TAG, "Dump took ${System.currentTimeMillis() - started} ms")
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
