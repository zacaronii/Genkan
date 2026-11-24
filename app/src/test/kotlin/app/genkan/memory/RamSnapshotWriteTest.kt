package app.genkan.memory

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.reader.NowPlaying
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.GZIPInputStream

/** A snapshot as the inbox gets it: a Super Famicom game's work RAM and video RAM from one moment. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class RamSnapshotWriteTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val snapshots = RamSnapshots(context)
    private val game = NowPlaying.Game("game:7", "A game", "snes", 7)

    /** Memory that answers nothing: everything comes from the moment. */
    private val memory = object : MemorySource {
        override val regions: List<LongRange> = emptyList()
        override val maxRead = 4096
        override fun target(value: Long): Long? = null
        override fun read(address: Long, size: Int): ByteArray? = null
        override fun close() {}
    }

    private fun extras(ram: ByteArray, vram: ByteArray?) = object : RamSnapshots.Extras {
        override fun moment(ram: MainRam, scratch: File) = RamSnapshots.Moment(null, ramBytes, vram)
        val ramBytes = ram
    }

    private fun gunzip(file: File) = GZIPInputStream(file.inputStream()).use { it.readBytes() }

    @Test
    fun `a Super Famicom snapshot is its work RAM at 0x7E0000 with the video RAM of the same moment`() {
        val ram = ByteArray(128 * 1024) { (it % 251).toByte() }
        val vram = ByteArray(64 * 1024) { (it % 13).toByte() }
        assertTrue(snapshots.takeNow(memory, game, "SHVC-ZQ", "あいうえお", "test", extras(ram, vram), "taken on demand"))
        val meta = snapshots.pending().single()
        val json = JSONObject(meta.readText())
        assertEquals("sfc", json.getString("platform"))
        assertEquals("0x007E0000", json.getString("base_address"))
        assertEquals("SHVC-ZQ", json.getString("id"))
        assertEquals("sfc-vram", json.getString("vram_format"))
        assertEquals(vram.size, json.getJSONObject("vram").getInt("bytes"))
        assertFalse(json.has("no_text"))
        assertArrayEquals(ram, gunzip(snapshots.ramOf(meta)))
        assertArrayEquals(vram, gunzip(snapshots.vramOf(meta)))
    }

    /** A made-up GameCube MEM1: a disc header (id GTST01, magic C2339F3D at 0x1C, big-endian) and filler. */
    private fun mem1(id: String = "GTST01", swapped: Boolean = false): ByteArray {
        val ram = ByteArray(0x1800000) { (it % 247).toByte() }
        id.toByteArray(Charsets.US_ASCII).copyInto(ram, 0)
        val magic = byteArrayOf(0xC2.toByte(), 0x33, 0x9F.toByte(), 0x3D)
        (if (swapped) magic.reversedArray() else magic).copyInto(ram, 0x1C)
        return ram
    }

    private val gamecube = NowPlaying.Game("game:9", "A GameCube game", "ngc", 9)

    private fun gcExtras(ram: ByteArray, stateBytes: Long? = null) = object : RamSnapshots.Extras {
        override fun moment(ram: MainRam, scratch: File) = RamSnapshots.Moment(null, ramBytes, null)
        override fun state(file: File): Long? = stateBytes?.also { file.writeBytes(byteArrayOf(1, 2, 3)) }
        val ramBytes = ram
    }

    @Test
    fun `a GameCube snapshot is MEM1 whole at 0x80000000, in the console's byte order`() {
        val ram = mem1()
        assertEquals("GTST01", RamSnapshots.gameId("ngc", ram))
        assertTrue(snapshots.takeNow(memory, gamecube, "GTST01", "あいうえお", "test", gcExtras(ram, 40L * 1024 * 1024), "taken on demand"))
        val meta = snapshots.pending().single()
        val json = JSONObject(meta.readText())
        assertEquals("ngc", json.getString("platform"))
        assertEquals("0x80000000", json.getString("base_address"))
        assertEquals("GTST01", json.getString("id"))
        assertEquals(40L * 1024 * 1024, json.getJSONObject("state").getLong("bytes"))
        val sent = gunzip(snapshots.ramOf(meta))
        assertEquals(0x1800000, sent.size)
        assertArrayEquals(ram, sent)
        assertEquals("C2339F3D", sent.copyOfRange(0x1C, 0x20).joinToString("") { "%02X".format(it) })
    }

    @Test
    fun `a GameCube state goes up to the inbox's 128 MB, a larger one is left out`() {
        // Dolphin's is about 93 MB: over the other platforms' 64 MB, sent for the GameCube.
        assertTrue(snapshots.takeNow(memory, gamecube, "GTST01", "", "test", gcExtras(mem1(), 93L * 1024 * 1024), "taken on demand: no text on screen"))
        val kept = snapshots.pending().single()
        assertEquals(93L * 1024 * 1024, JSONObject(kept.readText()).getJSONObject("state").getLong("bytes"))
        assertTrue(snapshots.stateOf(kept).isFile)
        snapshots.remove(kept)
        assertTrue(snapshots.takeNow(memory, gamecube, "GTST01", "", "test", gcExtras(mem1(), RamSnapshots.MAX_GC_STATE + 1), "taken on demand: no text on screen"))
        val meta = snapshots.pending().single()
        assertFalse(JSONObject(meta.readText()).has("state"))
        assertFalse(snapshots.stateOf(meta).exists())
    }

    @Test
    fun `the state cap is 128 MB for the GameCube, 64 MB for the rest`() {
        assertEquals(128L * 1024 * 1024, RamSnapshots.maxState("ngc"))
        assertEquals(64L * 1024 * 1024, RamSnapshots.maxState("psx"))
        assertEquals(64L * 1024 * 1024, RamSnapshots.maxState("snes"))
        // A Super Famicom state over 64 MB stays behind.
        assertTrue(snapshots.takeNow(memory, game, "SHVC-ZQ", "あいうえお", "test", object : RamSnapshots.Extras {
            override fun moment(ram: MainRam, scratch: File) = RamSnapshots.Moment(null, ByteArray(128 * 1024), null)
            override fun state(file: File): Long = (RamSnapshots.MAX_STATE + 1).also { file.writeBytes(byteArrayOf(1)) }
        }, "taken on demand"))
        assertFalse(JSONObject(snapshots.pending().single().readText()).has("state"))
    }

    @Test
    fun `MEM1 that isn't the disc's, or is word-swapped, is no snapshot`() {
        assertFalse(snapshots.takeNow(memory, gamecube, "GTST01", "あいうえお", "test", gcExtras(mem1(id = "GOTH02")), "taken on demand"))
        assertFalse(snapshots.takeNow(memory, gamecube, "GTST01", "あいうえお", "test", gcExtras(mem1(swapped = true)), "taken on demand"))
        assertTrue(snapshots.pending().isEmpty())
        assertEquals(null, RamSnapshots.gameId("ngc", mem1(swapped = true)))
        assertEquals(null, RamSnapshots.gameId("ngc", ByteArray(0x1800000)))
    }

    @Test
    fun `a press with OCR off says the screen wasn't read, not that it had no text`() {
        assertTrue(snapshots.takeNow(memory, gamecube, "GTST01", "", "test", gcExtras(mem1()), "taken on demand: OCR off, screen not read", ocrUnavailable = true))
        val json = JSONObject(snapshots.pending().single().readText())
        assertTrue(json.getBoolean("ocr_unavailable"))
        assertFalse(json.has("no_text"))
        assertEquals(0, json.getJSONArray("ocr").length())
    }

    @Test
    fun `a press with nothing read is a no-text snapshot, not refused`() {
        assertTrue(snapshots.takeNow(memory, game, "SHVC-ZQ", "", "test", extras(ByteArray(128 * 1024), null), "taken on demand: no text on screen"))
        val json = JSONObject(snapshots.pending().single().readText())
        assertTrue(json.getBoolean("no_text"))
        assertEquals(0, json.getJSONArray("ocr").length())
        assertFalse("no video RAM from this core", json.has("vram_format"))
    }
}
