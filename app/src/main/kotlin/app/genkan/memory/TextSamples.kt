package app.genkan.memory

import android.os.Environment
import android.util.Log
import app.genkan.BuildConfig
import app.genkan.core.PlayerSettings
import app.genkan.reader.NowPlaying
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Memory samples for building a game's character table in Yomikata (the reading catalog):
 * when a line OCR read isn't in memory in any standard encoding, the places whose codes have
 * its shape ([TextShape]) are saved with the bytes around them, next to what OCR read. A few
 * lines with known kana pin a game's byte order and kana order.
 *
 * The files hold script text, so they stay on the device (Download/Genkan/yomikata-samples)
 * for the player to hand over; nothing is sent anywhere. Off unless switched on in settings.
 */
@Singleton
class TextSamples @Inject constructor(private val settings: PlayerSettings) {
    val dir: File get() = File(Environment.getExternalStorageDirectory(), "Download/Genkan/yomikata-samples")

    suspend fun enabled(): Boolean = settings.samplesEnabled()

    /**
     * Looks for OCR's [line] by shape in [memory], trying a few stretches of it, and saves what
     * the first one that is placed found; returns how many places.
     */
    fun sample(memory: MemorySource, game: NowPlaying.Game, line: String): Int {
        val probes = TextShape.probes(line)
        if (probes.isEmpty()) return 0
        var total = 0L
        val regions = memory.regions.filter { total += it.last - it.first + 1; total <= MAX_MEMORY }
        val memoryBytes = regions.mapNotNull { region -> readAll(memory, region)?.let { region to it } }
        val serial = memoryBytes.firstNotNullOfOrNull { ps1Serial(it.second) }
        for (probe in probes) {
            val found = memoryBytes.flatMap { (region, bytes) -> TextShape.find(bytes, probe).map { Triple(region, bytes, it) } }
            if (found.isEmpty()) continue
            if (found.size > MAX_HITS) {
                Log.i(TAG, "${found.size} places have the shape of ${probe.take(6)}…: too many to be useful")
                continue
            }
            val samples = JSONArray()
            for ((region, bytes, hit) in found) {
                val from = maxOf(0, hit.offset - CONTEXT)
                val to = minOf(bytes.size, hit.offset + hit.length + CONTEXT)
                samples.put(
                    JSONObject()
                        .put("ocr", line)
                        .put("probe", probe)
                        .put("address", "0x%08X".format(region.first + hit.offset))
                        .put("unit", hit.unit)
                        .put("byte_order", if (hit.bigEndian) "big" else "little")
                        .put("context_start", "0x%08X".format(region.first + from))
                        .put("bytes_hex", bytes.copyOfRange(from, to).joinToString("") { "%02x".format(it) }),
                )
            }
            save(game, serial, samples)
            Log.i(TAG, "${samples.length()} places have the shape of ${probe.take(6)}…: saved")
            return samples.length()
        }
        Log.i(TAG, "No stretch of \"${line.take(12)}\" was placed by its shape (${probes.size} tried)")
        return 0
    }

    private fun save(game: NowPlaying.Game, serial: String?, add: JSONArray) {
        val name = (serial ?: "rom-${game.rommRomId ?: game.key}").replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val file = File(dir.apply { mkdirs() }, "${game.platform ?: "game"}-$name.json")
        val doc = runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()
            .put("id", serial ?: JSONObject.NULL)
            .put("platform", game.platform ?: JSONObject.NULL)
            .put("title", game.title)
            .put("romm_rom_id", game.rommRomId ?: JSONObject.NULL)
            .put("exe_sha1", JSONObject.NULL)
            .put("samples", JSONArray())
        // Yomikata's platform names, not RomM's (psx is ps1 there); files written before are fixed here too.
        doc.put("platform", game.platform?.let(::yomikataPlatform) ?: JSONObject.NULL)
        doc.put("genkan_version", BuildConfig.VERSION_NAME)
        val samples = doc.getJSONArray("samples")
        for (i in 0 until add.length()) if (samples.length() < MAX_SAMPLES) samples.put(add.get(i))
        file.writeText(doc.toString(1))
        Log.i(TAG, "${add.length()} samples for ${game.title} in ${file.name} (${samples.length()} in all)")
    }

    private fun readAll(memory: MemorySource, region: LongRange): ByteArray? {
        val size = (region.last - region.first + 1).toInt()
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            val n = minOf(memory.maxRead, size - done)
            memory.read(region.first + done, n)?.copyInto(out, done) ?: return null
            done += n
        }
        return out
    }

    companion object {
        /** A RomM platform slug as Yomikata names the platform (docs/format.md there). */
        fun yomikataPlatform(slug: String): String = YOMIKATA_PLATFORMS[slug] ?: slug

        private val YOMIKATA_PLATFORMS = mapOf(
            "psx" to "ps1", "ngc" to "gc", "snes" to "sfc", "sfam" to "sfc", "nes" to "fc", "famicom" to "fc",
            "genesis" to "md", "genesis-slash-megadrive" to "md", "megadrive" to "md",
        )
        private const val TAG = "TextSamples"
        /** Search no more than this much memory (a PS1 has 2 MB; a 3DS hundreds). */
        private const val MAX_MEMORY = 8L * 1024 * 1024
        private const val MAX_HITS = 8
        private const val MAX_SAMPLES = 60
        private const val CONTEXT = 256

        /** The PS1 BIOS leaves the boot path in RAM: cdrom:\SLPS_010.01;1 → SLPS-01001. */
        private val BOOT = Regex("""cdrom:\\{1,2}([A-Z]{4})_(\d{3})\.(\d{2})""", RegexOption.IGNORE_CASE)

        internal fun ps1Serial(bytes: ByteArray): String? {
            val ascii = String(bytes, Charsets.ISO_8859_1)
            val m = BOOT.find(ascii) ?: return null
            return "${m.groupValues[1].uppercase()}-${m.groupValues[2]}${m.groupValues[3]}"
        }
    }
}
