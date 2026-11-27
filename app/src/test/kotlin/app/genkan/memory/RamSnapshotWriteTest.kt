package app.genkan.memory

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.reader.NowPlaying
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private val saturn = NowPlaying.Game("game:11", "A Saturn game", "saturn", 11)

    @Test
    fun `a Saturn snapshot carries its low 1 MB as the region lwram, sent as its own part`() {
        val high = ByteArray(0x100000) { (it % 241).toByte() }
        val low = ByteArray(0x100000) { (it % 239).toByte() }
        // The low 1 MB read through the player's memory, at the console's addresses.
        val lowMemory = object : MemorySource {
            override val regions: List<LongRange> = listOf(0x00200000L until 0x00300000L)
            override val maxRead = 4096
            override fun target(value: Long): Long? = null
            override fun read(address: Long, size: Int): ByteArray? =
                if (address in 0x00200000L until 0x00300000L) low.copyOfRange((address - 0x00200000L).toInt(), (address - 0x00200000L).toInt() + size) else null
            override fun close() {}
        }
        assertTrue(snapshots.takeNow(lowMemory, saturn, "ABCD-00001", "あいうえお", "test", extras(high, null), "taken on demand"))
        val meta = snapshots.pending().single()
        val json = JSONObject(meta.readText())
        assertEquals("saturn", json.getString("platform"))
        assertEquals("0x06000000", json.getString("base_address"))
        val lwram = json.getJSONObject("regions").getJSONObject("lwram")
        assertEquals("0x00200000", lwram.getString("base_address"))
        assertEquals(0x100000, lwram.getInt("bytes"))
        assertArrayEquals(high, gunzip(snapshots.ramOf(meta)))
        assertArrayEquals(low, gunzip(snapshots.regionOf(meta, "lwram")))
        assertEquals(listOf("lwram"), snapshots.partsOf(meta).map { it.first })
        assertTrue(snapshots.hasExtras(meta))
        snapshots.remove(meta)
        assertFalse(snapshots.regionOf(meta, "lwram").exists())
    }

    @Test
    fun `a region read across the source's own regions is read a part at a time`() {
        // mGBA's map: I/O at 0xFF00-0xFF7F, high RAM to 0xFFFE, the interrupt register at 0xFFFF; a
        // read can't cross from one into the next.
        val io = ByteArray(256) { (it + 1).toByte() }
        val parts = listOf(0xFF00L..0xFF7FL, 0xFF80L..0xFFFEL, 0xFFFFL..0xFFFFL)
        val gb = object : MemorySource {
            override val regions: List<LongRange> = parts
            override val maxRead = 4096
            override fun target(value: Long): Long? = null
            override fun read(address: Long, size: Int): ByteArray? =
                parts.firstOrNull { address in it && address + size - 1 in it }?.let { io.copyOfRange((address - 0xFF00).toInt(), (address - 0xFF00).toInt() + size) }
            override fun close() {}
        }
        assertArrayEquals(io, RamSnapshots.readRam(gb, MainRam(0xFF00L until 0x10000L, 0xFF00L)))
    }

    @Test
    fun `regions as Yomikata's inbox takes them, and a snapshot without one sends no region part`() {
        assertEquals(listOf("lwram"), SnapshotRegion.of("saturn").map { it.name })
        // A Game Boy's I/O and high RAM, 256 bytes from 0xFF00; a PC Engine CD's own 8 KB at 0x1F0000.
        listOf("gb", "gbc").forEach { assertEquals(listOf(SnapshotRegion("io", 0xFF00L until 0x10000L, 0xFF00L)), SnapshotRegion.of(it)) }
        assertEquals(listOf(SnapshotRegion("wram", 0x1F0000L until 0x1F2000L, 0x1F0000L)), SnapshotRegion.of("turbografx-cd"))
        assertEquals(256, SnapshotRegion.of("gb").single().range.count())
        assertEquals(8 * 1024, SnapshotRegion.of("turbografx-cd").single().range.count())
        assertEquals(listOf("lwram", "io", "wram"), SnapshotRegion.NAMES)
        // A Famicom's and a Disk System's work RAM at 0x6000 (fceumm's 1 KB pages, read a part at a
        // time), and their nametables as video memory, where fceumm's map puts them.
        listOf("nes", "famicom", "fds").forEach {
            assertEquals(listOf(SnapshotRegion("wram", 0x6000L until 0x8000L, 0x6000L)), SnapshotRegion.of(it))
            assertEquals(MainRam(0x80002000L until 0x80002800L, 0x80002000L), SnapshotRegion.mappedVideo(it))
            assertEquals(MainRam(0x0L until 0x800L, 0x0L), MainRam.of(it))
        }
        assertNull(SnapshotRegion.mappedVideo("snes"))
        listOf("psx", "psp", "snes", "gba", "nds", "ngc", "wii", "dc", "tg16", "ps2").forEach { assertEquals(it, emptyList<SnapshotRegion>(), SnapshotRegion.of(it)) }
        assertTrue(snapshots.takeNow(memory, game, "SHVC-ZQ", "あいうえお", "test", extras(ByteArray(128 * 1024), ByteArray(64 * 1024)), "taken on demand"))
        val meta = snapshots.pending().single()
        assertFalse(JSONObject(meta.readText()).has("regions"))
        assertEquals(listOf("vram"), snapshots.partsOf(meta).map { it.first })
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
    fun `the screen is read once the moment is taken, and its line is the snapshot's`() {
        val order = mutableListOf<String>()
        val extras = object : RamSnapshots.Extras {
            override fun moment(ram: MainRam, scratch: File): RamSnapshots.Moment {
                order += "moment"
                return RamSnapshots.Moment(null, mem1(), null)
            }
        }
        assertTrue(
            snapshots.takeNow(memory, gamecube, "GTST01", "", "test", extras, "taken on demand", ocrUnavailable = true,
                screenLine = { order += "ocr"; "今の画面の行" }),
        )
        assertEquals(listOf("moment", "ocr"), order)
        val json = JSONObject(snapshots.pending().single().readText())
        assertEquals("今の画面の行", json.getJSONArray("ocr").getString(0))
        assertFalse(json.has("ocr_unavailable"))
        assertFalse(json.has("no_text"))
    }

    @Test
    fun `a screen read that finds nothing says the screen wasn't read`() {
        assertTrue(snapshots.takeNow(memory, gamecube, "GTST01", "", "test", gcExtras(mem1()), "taken on demand", ocrUnavailable = true, screenLine = { null }))
        val json = JSONObject(snapshots.pending().single().readText())
        assertTrue(json.getBoolean("ocr_unavailable"))
        assertEquals(0, json.getJSONArray("ocr").length())
    }

    @Test
    fun `a line already read is kept, the screen not read again`() {
        var asked = false
        assertTrue(snapshots.takeNow(memory, gamecube, "GTST01", "レイアウトの行", "test", gcExtras(mem1()), "taken on demand", screenLine = { asked = true; "別の行" }))
        assertFalse(asked)
        assertEquals("レイアウトの行", JSONObject(snapshots.pending().single().readText()).getJSONArray("ocr").getString(0))
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
