package app.genkan.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RamSnapshotsTest {

    @Test
    fun `a clean line is mostly kana and kanji`() {
        // What ML Kit read off Moonlight Syndrome's first line: a stray mark is fine.
        assertTrue(RamSnapshots.isClean("o.あんなのだったらもっと早く帰ってくればよかった▼"))
        assertFalse(RamSnapshots.isClean("あの。。岡9て金す?,")) // a line still typing out, misread
        assertFalse(RamSnapshots.isClean("(C)1997HUMANIT0ALLBIGHTSRESERVED"))
        assertFalse(RamSnapshots.isClean("消えた·?"))
    }

    @Test
    fun `the release id comes from the game's RAM`() {
        val ps1 = ByteArray(64) + "cdrom:\\SLPS_010.01;1".toByteArray() + ByteArray(16)
        assertEquals("SLPS-01001", RamSnapshots.gameId("psx", ps1))
        val psp = "x/PSP/SAVEDATA/NPJH50372DATA0\u0000NPJH50372\u0000ULJM05500X".toByteArray(Charsets.ISO_8859_1)
        assertEquals("NPJH50372", RamSnapshots.gameId("psp", psp))
        assertNull(RamSnapshots.gameId("psp", ByteArray(32)))
    }

    @Test
    fun `main RAM is where the inbox expects a dump`() {
        assertEquals(0x80000000L, MainRam.of("psx")!!.baseAddress)
        assertEquals(2L * 1024 * 1024, MainRam.of("psx")!!.range.let { it.last - it.first + 1 })
        assertEquals(32L * 1024 * 1024, MainRam.of("psp")!!.range.let { it.last - it.first + 1 })
        fun size(platform: String) = MainRam.of(platform)!!.range.let { it.last - it.first + 1 }
        assertEquals(0x7E0000L, MainRam.of("snes")!!.baseAddress)
        assertEquals(128L * 1024, size("snes"))
        assertEquals(MainRam.of("snes"), MainRam.of("sfam"))
        assertEquals(0x02000000L, MainRam.of("gba")!!.baseAddress)
        assertEquals(256L * 1024, size("gba"))
        assertEquals(0xC000L, MainRam.of("gb")!!.baseAddress)
        assertEquals(8L * 1024, size("gbc"))
        assertEquals(0x02000000L, MainRam.of("nds")!!.baseAddress)
        assertEquals(4L * 1024 * 1024, size("nds"))
        assertEquals(0x80000000L, MainRam.of("ngc")!!.baseAddress)
        assertEquals(24L * 1024 * 1024, size("ngc"))
        assertNull(MainRam.of("n64"))
    }

    @Test
    fun `a GameCube build is its disc id and version`() {
        val ram = ByteArray(0x40)
        "GTST01".toByteArray().copyInto(ram)
        ram[7] = 0x02
        byteArrayOf(0xC2.toByte(), 0x33, 0x9F.toByte(), 0x3D).copyInto(ram, 0x1C)
        assertEquals("GTST01-02", RamSnapshots.gcBuildId(ram))
        assertNull(RamSnapshots.gcBuildId(ByteArray(0x40)))
    }

    @Test
    fun `a Wii disc is known by its own magic word, its build the same way`() {
        // A made-up disc id.
        val ram = ByteArray(0x40)
        "RTST01".toByteArray().copyInto(ram)
        ram[7] = 0x01
        byteArrayOf(0x5D, 0x1C, 0x9E.toByte(), 0xA3.toByte()).copyInto(ram, 0x18)
        assertEquals("RTST01", RamSnapshots.gameId("wii", ram))
        assertEquals("RTST01-01", RamSnapshots.wiiBuildId(ram))
        assertEquals("RTST01-01", app.genkan.core.ScriptEvents.buildId("wii", ram, null))
        // Not a GameCube disc, and a GameCube's header isn't a Wii's.
        assertNull(RamSnapshots.gcDiscId(ram))
        val gc = ByteArray(0x40).also { "GTST01".toByteArray().copyInto(it); byteArrayOf(0xC2.toByte(), 0x33, 0x9F.toByte(), 0x3D).copyInto(it, 0x1C) }
        assertNull(RamSnapshots.gameId("wii", gc))
        assertNull(RamSnapshots.wiiBuildId(ByteArray(0x40)))
        assertEquals(MainRam.of("ngc"), MainRam.of("wii"))
    }

    @Test
    fun `video RAM is labelled by console, PS1's as its frame buffer`() {
        assertEquals("ps1-15bit", RamSnapshots.vramFormat("psx"))
        assertEquals("sfc-vram", RamSnapshots.vramFormat("snes"))
        assertEquals("sfc-vram", RamSnapshots.vramFormat("sfam"))
        assertEquals("gba-vram", RamSnapshots.vramFormat("gba"))
        assertEquals("gb-vram", RamSnapshots.vramFormat("gb"))
        assertEquals("gbc-vram", RamSnapshots.vramFormat("gbc"))
        assertEquals("nds-vram", RamSnapshots.vramFormat("nds"))
    }
}
