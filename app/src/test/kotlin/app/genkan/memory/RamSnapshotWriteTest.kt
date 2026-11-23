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

    @Test
    fun `a press with nothing read is a no-text snapshot, not refused`() {
        assertTrue(snapshots.takeNow(memory, game, "SHVC-ZQ", "", "test", extras(ByteArray(128 * 1024), null), "taken on demand: no text on screen"))
        val json = JSONObject(snapshots.pending().single().readText())
        assertTrue(json.getBoolean("no_text"))
        assertEquals(0, json.getJSONArray("ocr").length())
        assertFalse("no video RAM from this core", json.has("vram_format"))
    }
}
