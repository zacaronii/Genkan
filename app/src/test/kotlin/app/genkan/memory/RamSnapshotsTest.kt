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
        assertNull(MainRam.of("gba"))
    }
}
