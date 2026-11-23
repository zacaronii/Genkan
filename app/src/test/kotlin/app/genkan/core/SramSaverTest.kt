package app.genkan.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The battery save: never asked of a core with no game in it, never written over by nothing. */
class SramSaverTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `a load that failed - pause and the save tick ask nothing of the core and leave the save as it was`() {
        val save = temp.newFile("game.srm").apply { writeBytes(ByteArray(512) { it.toByte() }) }
        val before = save.readBytes()
        val modified = save.lastModified()
        var asked = 0
        val saver = SramSaver(save, loaded = { false }, read = { asked++; error("the core would crash here") }, last = before)
        // onPause, then the 30-second tick, then quit.
        repeat(3) { assertFalse(saver.save()) }
        assertEquals(0, asked)
        assertArrayEquals(before, save.readBytes())
        assertEquals(modified, save.lastModified())
    }

    @Test
    fun `a loaded game's changed save is written, an unchanged one isn't`() {
        val save = temp.root.resolve("saves/game.srm")
        var sram = ByteArray(8) { 1 }
        val saver = SramSaver(save, loaded = { true }, read = { sram }, last = sram.copyOf())
        assertFalse("unchanged since it started", saver.save())
        assertFalse(save.exists())
        sram = ByteArray(8) { 2 }
        assertTrue(saver.save())
        assertArrayEquals(sram, save.readBytes())
        assertFalse("written once", saver.save())
    }

    @Test
    fun `an empty save RAM writes nothing`() {
        val save = temp.newFile("game.srm").apply { writeBytes(byteArrayOf(9, 9)) }
        assertFalse(SramSaver(save, loaded = { true }, read = { ByteArray(0) }).save())
        assertArrayEquals(byteArrayOf(9, 9), save.readBytes())
    }
}
