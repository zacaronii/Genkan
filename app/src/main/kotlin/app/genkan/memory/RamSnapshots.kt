package app.genkan.memory

import android.content.Context
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
data class MainRam(val range: LongRange, val baseAddress: Long) {
    companion object {
        /** By RomM platform slug; null for systems Yomikata takes no snapshots of. */
        fun of(platform: String?): MainRam? = when (platform) {
            // 2 MB, seen by games at 0x80000000; the core reads it from 0.
            "psx" -> MainRam(0x0L until 0x200000L, 0x80000000L)
            // User RAM, the 32 MB from 0x08000000.
            "psp" -> MainRam(0x08000000L until 0x0A000000L, 0x08000000L)
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
    fun pending(): List<File> = dir.listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty().sortedBy { it.name }

    fun ramOf(meta: File): File = File(meta.path.removeSuffix(".meta.json") + ".ram.gz")

    /** The video RAM taken with a snapshot, when there is one. */
    fun vramOf(meta: File): File = File(meta.path.removeSuffix(".meta.json") + ".vram.gz")

    /** Sent or refused for good: gone from the device. */
    fun remove(meta: File) {
        ramOf(meta).delete()
        vramOf(meta).delete()
        meta.delete()
    }

    /**
     * Takes a snapshot of [memory]'s main RAM for [line] when it's clean and new for the game and
     * the game has fewer than [PER_GAME]; returns whether it did. [vram], when the core gives
     * it, goes along: on a PS1 it holds the scene's font and the text box as drawn, which can
     * map kanji codes when the script isn't in RAM as codes (Persona 2).
     */
    @Synchronized
    fun take(memory: MemorySource, game: NowPlaying.Game, id: String, line: String, emulator: String, vram: (() -> ByteArray?)? = null): Boolean {
        val ram = MainRam.of(game.platform) ?: return false
        if (!isClean(line)) return false
        val taken = runCatching { JSONObject(ledger.readText()) }.getOrNull() ?: JSONObject()
        val lines = taken.optJSONArray(id) ?: JSONArray()
        val key = layoutFree(line)
        if (lines.length() >= PER_GAME) return false
        // OCR flickers on a line (よかった, よなった): one that close is the same line, maybe misread.
        if ((0 until lines.length()).any { HookSearch.similarity(lines.getString(it), key) >= SAME_LINE }) return false
        // A misread is a false constraint on the table: only a line OCR has read the same way
        // twice, or a long one (a story told line by line is read once each), where one is a small part.
        if (seen.add(key) && key.count { isJapanese(it) } < LONG_LINE) return false
        // What can't be read stays zeros: the dump keeps its addresses.
        val bytes = readRam(memory, ram) ?: return false
        dir.mkdirs()
        val name = "$id-${System.currentTimeMillis()}".replace(Regex("[^A-Za-z0-9_.-]"), "_")
        GZIPOutputStream(File(dir, "$name.ram.gz").outputStream()).use { it.write(bytes) }
        val meta = JSONObject()
            .put("id", id)
            .put("platform", TextSamples.yomikataPlatform(game.platform!!))
            .put("base_address", "0x%08X".format(ram.baseAddress))
            .put("ocr", JSONArray(listOf(line)))
            .put("title", game.title)
            .put("app", "Genkan ${BuildConfig.VERSION_NAME}")
            .put("emulator", emulator)
            .put("captured_at", Instant.now().toString())
        vram?.invoke()?.let { video ->
            GZIPOutputStream(File(dir, "$name.vram.gz").outputStream()).use { it.write(video) }
            meta.put("vram", JSONObject().put("bytes", video.size).put("width", VRAM_WIDTH).put("height", video.size / 2 / VRAM_WIDTH).put("format", "ps1-15bit"))
        }
        File(dir, "$name.meta.json").writeText(meta.toString())
        taken.put(id, lines.put(key))
        ledger.writeText(taken.toString())
        Log.i(TAG, "Snapshot ${lines.length()} of $PER_GAME for ${game.title} ($id): ${bytes.size / 1024} KB at \"${line.take(12)}\"")
        return true
    }

    companion object {
        private const val TAG = "RamSnapshots"
        const val PER_GAME = 5
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
