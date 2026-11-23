package app.genkan.memory

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import app.genkan.BuildConfig
import app.genkan.ppsspp.HookSearch
import app.genkan.reader.NowPlaying
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A system's main RAM: where it is in the memory a [MemorySource] reads, and where the game sees it. */
/** A capture file older than this belongs to no snapshot being written. */
private const val STALE_CAPTURE_MS = 60_000L

data class MainRam(val range: LongRange, val baseAddress: Long) {
    companion object {
        /** By RomM platform slug; null for systems Yomikata takes no snapshots of. */
        fun of(platform: String?): MainRam? = when (platform) {
            // 2 MB, seen by games at 0x80000000; the core reads it from 0.
            "psx" -> MainRam(0x0L until 0x200000L, 0x80000000L)
            // User RAM, the 32 MB from 0x08000000.
            "psp" -> MainRam(0x08000000L until 0x0A000000L, 0x08000000L)
            // Work RAM, the 128 KB at 0x7E0000 (bsnes-mercury's memory map gives the bus's addresses).
            "snes", "sfam" -> MainRam(0x7E0000L until 0x800000L, 0x7E0000L)
            // The 256 KB of external work RAM at 0x02000000, as mGBA maps it.
            "gba" -> MainRam(0x02000000L until 0x02040000L, 0x02000000L)
            // Work RAM's 8 KB window at 0xC000.
            "gb", "gbc" -> MainRam(0xC000L until 0xE000L, 0xC000L)
            // Main RAM, 4 MB at 0x02000000 (melonDS's, which Genkan reads from that base).
            "nds" -> MainRam(0x02000000L until 0x02400000L, 0x02000000L)
            else -> null
        }
    }
}

/**
 * Whole-RAM snapshots for Yomikata (docs/inbox.md there, `POST /api/snapshots`), taken while a
 * line OCR read cleanly is on screen in a game whose text isn't in a standard encoding: the
 * inbox's side searches them, all lines at once, to work out the game's own table. A few per game,
 * each at a different line; they wait in Genkan's own storage until [SampleInbox] sends them,
 * and hold script text, so they go nowhere else.
 */
@Singleton
class RamSnapshots @Inject constructor(@ApplicationContext private val context: Context) {
    val dir: File get() = File(context.filesDir, "yomikata-snapshots")
    private val ledger: File get() = File(dir, "taken.json")
    /** Lines offered once so far, this run. */
    private val seen = HashSet<String>()

    /** Snapshots waiting to be sent: each one's meta file (its RAM beside it, `.ram.gz`). */
    fun pending(): List<File> {
        // A capture's memory left behind by a process that died mid-snapshot.
        dir.listFiles { f -> (f.name.endsWith(".capture") || f.name.endsWith(".capture.vram")) && f.lastModified() < System.currentTimeMillis() - STALE_CAPTURE_MS }
            ?.forEach { it.delete() }
        return dir.listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty().sortedBy { it.name }
    }

    fun ramOf(meta: File): File = File(meta.path.removeSuffix(".meta.json") + ".ram.gz")

    /** The video RAM taken with a snapshot, when there is one. */
    fun vramOf(meta: File): File = File(meta.path.removeSuffix(".meta.json") + ".vram.gz")

    /** The core's savestate taken with a snapshot, gzipped. */
    fun stateOf(meta: File): File = File(meta.path.removeSuffix(".meta.json") + ".state.gz")

    /** The frame on screen when the snapshot was taken, as a PNG. */
    fun screenOf(meta: File): File = File(meta.path.removeSuffix(".meta.json") + ".screen.png")

    /** Whether a snapshot carries anything besides RAM. */
    fun hasExtras(meta: File): Boolean = vramOf(meta).isFile || stateOf(meta).isFile || screenOf(meta).isFile

    /** What the emulator can add to a snapshot besides RAM, for the inbox's side to work from. */
    interface Extras {
        /** The video RAM, when the core gives it out. */
        fun vram(): ByteArray? = null
        /** The savestate, gzipped into [file]; its size before gzip, or null. */
        fun state(file: File): Long? = null
        /** The frame on screen. */
        fun screen(): Bitmap? = null
        /**
         * The frame, [ram]'s bytes and the video RAM from the same moment, the game held still
         * between them, through [scratch]; null when the emulator can't, and [screen], a plain
         * read and [vram] are used.
         */
        fun moment(ram: MainRam, scratch: File): Moment? = null
    }

    /** One moment of the game: its frame, RAM and video RAM (null where the core gives none). */
    class Moment(val frame: Bitmap?, val ram: ByteArray, val vram: ByteArray?)

    /** Sent or refused for good: gone from the device. */
    fun remove(meta: File) {
        ramOf(meta).delete()
        vramOf(meta).delete()
        stateOf(meta).delete()
        screenOf(meta).delete()
        meta.delete()
    }

    /**
     * Takes a snapshot of [memory]'s main RAM for [line] when it's clean and new for the game and
     * the game has fewer than its [cap]; returns whether it did. What [extras] gives goes along
     * (the savestate, holding a PS1's video RAM with the scene's font, and the frame on screen):
     * the inbox's side ties glyphs to characters with them when a script isn't in RAM as codes.
     */
    @Synchronized
    fun take(memory: MemorySource, game: NowPlaying.Game, id: String, line: String, emulator: String, extras: Extras? = null): Boolean {
        val ram = MainRam.of(game.platform) ?: return false
        if (!isClean(line)) return false
        val taken = runCatching { JSONObject(ledger.readText()) }.getOrNull() ?: JSONObject()
        val lines = taken.optJSONArray(id) ?: JSONArray()
        val key = layoutFree(line)
        if (lines.length() >= cap(id)) return false
        // OCR flickers on a line (よかった, よなった): one that close is the same line, maybe misread.
        if ((0 until lines.length()).any { HookSearch.similarity(lines.getString(it), key) >= SAME_LINE }) return false
        // A misread is a false constraint on the table: only a line OCR has read the same way
        // twice, or a long one (a story told line by line is read once each), where one is a small part.
        if (seen.add(key) && key.count { isJapanese(it) } < LONG_LINE) return false
        if (!write(memory, game, ram, id, line, emulator, extras, note = null)) return false
        taken.put(id, lines.put(key))
        ledger.writeText(taken.toString())
        return true
    }

    /**
     * A snapshot taken when asked (a line still typing out, or waiting for a press), with none
     * of [take]'s rules and not counted against them; [note] says what moment it caught.
     */
    @Synchronized
    fun takeNow(memory: MemorySource, game: NowPlaying.Game, id: String, line: String, emulator: String, extras: Extras?, note: String): Boolean {
        val ram = MainRam.of(game.platform) ?: return false
        return write(memory, game, ram, id, line, emulator, extras, note)
    }

    private fun write(
        memory: MemorySource,
        game: NowPlaying.Game,
        ram: MainRam,
        id: String,
        line: String,
        emulator: String,
        extras: Extras?,
        note: String?,
    ): Boolean {
        dir.mkdirs()
        val name = "$id-${System.currentTimeMillis()}".replace(Regex("[^A-Za-z0-9_.-]"), "_")
        // The frame and the memory of one moment, where the emulator can hold the game still
        // between them. Else the picture first, then the memory at once, as close as reads get:
        // the game runs meanwhile, so a line typing out can be ahead in memory.
        val moment = extras?.moment(ram, File(dir, "$name.capture"))
        // A moment without its frame goes without a picture: a later one would claim the
        // memory's moment and not be it.
        val frame = if (moment != null) moment.frame else extras?.screen()
        // What can't be read stays zeros: the dump keeps its addresses.
        val bytes = moment?.ram ?: readRam(memory, ram) ?: return false
        GZIPOutputStream(File(dir, "$name.ram.gz").outputStream()).use { it.write(bytes) }
        val meta = JSONObject()
            .put("id", id)
            .put("platform", TextSamples.yomikataPlatform(game.platform!!))
            .put("base_address", "0x%08X".format(ram.baseAddress))
            // No text on screen (a scene change, no window): said so, as the inbox asks.
            .put("ocr", JSONArray(listOfNotNull(line.takeIf { it.isNotBlank() })))
            .put("title", game.title)
            .put("app", "Genkan ${BuildConfig.VERSION_NAME}")
            .put("emulator", emulator)
            .put("captured_at", Instant.now().toString())
        note?.let { meta.put("note", it) }
        if (line.isBlank()) meta.put("no_text", true)
        (if (moment != null) moment.vram else extras?.vram())?.let { video ->
            GZIPOutputStream(File(dir, "$name.vram.gz").outputStream()).use { it.write(video) }
            val format = vramFormat(game.platform!!)
            val described = JSONObject().put("bytes", video.size).put("format", format)
            // A PS1's is a 1024-wide 16-bit frame buffer; other consoles' are their cores' raw blocks.
            if (format == PS1_VRAM) described.put("width", VRAM_WIDTH).put("height", video.size / 2 / VRAM_WIDTH)
            meta.put("vram", described).put("vram_format", format)
        }
        extras?.state(File(dir, "$name.state.gz"))?.let { size -> meta.put("state", JSONObject().put("bytes", size)) }
        frame?.let { frame ->
            File(dir, "$name.screen.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            meta.put("screen", JSONObject().put("width", frame.width).put("height", frame.height))
        }
        File(dir, "$name.meta.json").writeText(meta.toString())
        Log.i(TAG, "Snapshot${note?.let { " ($it)" }.orEmpty()} (${meta.optJSONObject("state")?.optLong("bytes")?.let { "state $it bytes" } ?: "no state"}) for ${game.title} ($id): ${bytes.size / 1024} KB at \"${line.take(12)}\"")
        return true
    }

    companion object {
        private const val TAG = "RamSnapshots"
        const val PER_GAME = 5
        private const val PS1_VRAM = "ps1-15bit"

        /** The label of a console's video RAM for the inbox (`meta.vram_format`): PS1's frame buffer, else the core's raw block. */
        fun vramFormat(platform: String): String = TextSamples.yomikataPlatform(platform).let { if (it == "ps1") PS1_VRAM else "$it-vram" }

        /** Games the Yomikata server is working on now, which want more lines. */
        private val ACTIVE = mapOf("SLPS-02100" to 10)

        fun cap(id: String): Int = ACTIVE[id] ?: PER_GAME
        /** A PS1's video RAM is 1024 16-bit pixels wide (15-bit colour, bit 15 the mask bit). */
        private const val VRAM_WIDTH = 1024
        private const val SAME_LINE = 0.8
        private const val LONG_LINE = 15

        /**
         * A line worth a snapshot: at least [MIN_JAPANESE] kana or kanji, and little else besides
         * punctuation (a misread line becomes a false constraint on the table).
         */
        fun isClean(line: String): Boolean {
            val text = layoutFree(line)
            val japanese = text.count { isJapanese(it) }
            val other = text.count { !isJapanese(it) && !isPunctuation(it) }
            return japanese >= MIN_JAPANESE && other * 10 <= text.length
        }

        private fun isJapanese(c: Char) = c in 'ぁ'..'ヿ' || c in '一'..'鿿' || c == '々'

        private fun isPunctuation(c: Char) = c in "。、・…！？「」『』（）ー〜―－！？!?.,:;▼▽△▲★☆♪\"'（）()" || c in '　'..'〿' || c in '！'..'／'

        private const val MIN_JAPANESE = 8

        private fun layoutFree(text: String): String = text.filterNot { it.isWhitespace() }

        /**
         * The release id the catalog knows the game by, from its RAM: a PS1 disc's boot path
         * (SLPS-01001), or the PSP disc id the game names its saves with (the commonest there).
         */
        fun gameId(platform: String?, ram: ByteArray): String? = when (platform) {
            "psx" -> TextSamples.ps1Serial(ram)
            "psp" -> PSP_ID.findAll(String(ram, Charsets.ISO_8859_1)).map { it.value }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            else -> null
        }

        private val PSP_ID = Regex("(?<![A-Z0-9])(?:UL|UC|NP)[A-Z]{2}\\d{5}(?![0-9])")

        /** [memory]'s main RAM for [platform], for reading the game's id; null when unreadable. */
        fun mainRamOf(memory: MemorySource, platform: String?): ByteArray? {
            val ram = MainRam.of(platform) ?: return null
            return readRam(memory, ram)
        }

        internal fun readRam(memory: MemorySource, ram: MainRam): ByteArray? {
            val size = (ram.range.last - ram.range.first + 1).toInt()
            val out = ByteArray(size)
            var any = false
            var at = 0
            while (at < size) {
                val n = minOf(memory.maxRead, size - at)
                memory.read(ram.range.first + at, n)?.let { it.copyInto(out, at, 0, minOf(it.size, n)); any = true }
                at += n
            }
            return out.takeIf { any }
        }
    }
}
