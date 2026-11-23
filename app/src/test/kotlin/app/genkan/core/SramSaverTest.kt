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

    private val keep get() = temp.root.resolve("game.srm" + SramSaver.KEEP_SUFFIX)

    @Test
    fun `a shared save of another size is kept once before it's first written`() {
        val other = ByteArray(2048) { 7 }
        val save = temp.newFile("game.srm").apply { writeBytes(other) }
        val sram = ByteArray(8192) { 3 }
        // The core loads what fits: its save RAM isn't the save's size.
        val saver = SramSaver(save, loaded = { true }, read = { sram }, last = other)
        assertTrue(saver.save())
        assertArrayEquals(other, keep.readBytes())
        assertArrayEquals(sram, save.readBytes())
        // A second session over what the first wrote: same size now, and the keep stays.
        val next = ByteArray(8192) { 4 }
        assertTrue(SramSaver(save, loaded = { true }, read = { next }, last = sram).save())
        assertArrayEquals(other, keep.readBytes())
    }

    @Test
    fun `an earlier keep is never overwritten`() {
        val save = temp.newFile("game.srm").apply { writeBytes(ByteArray(100) { 1 }) }
        keep.writeBytes(byteArrayOf(42))
        assertTrue(SramSaver(save, loaded = { true }, read = { ByteArray(200) { 2 } }, last = ByteArray(100) { 1 }).save())
        assertArrayEquals(byteArrayOf(42), keep.readBytes())
    }

    @Test
    fun `a save of the same size is written with no keep, and no save at start makes none`() {
        val save = temp.newFile("game.srm").apply { writeBytes(ByteArray(512) { 1 }) }
        assertTrue(SramSaver(save, loaded = { true }, read = { ByteArray(512) { 2 } }, last = ByteArray(512) { 1 }).save())
        assertFalse(keep.exists())
        val fresh = temp.root.resolve("new.srm")
        assertTrue(SramSaver(fresh, loaded = { true }, read = { ByteArray(512) { 3 } }, last = null).save())
        assertFalse(temp.root.resolve("new.srm" + SramSaver.KEEP_SUFFIX).exists())
    }

    @Test
    fun `a loaded state's save RAM isn't written over the battery save until the game changes it`() {
        val save = temp.root.resolve("saves/game.srm")
        val now = ByteArray(8) { 5 }
        save.parentFile!!.mkdirs()
        save.writeBytes(now)
        var sram = now.copyOf()
        val saver = SramSaver(save, loaded = { true }, read = { sram }, last = now.copyOf())
        // An old state's save RAM comes in with it: loading it is not the game saving.
        sram = ByteArray(8) { 1 }
        saver.stateLoaded()
        assertFalse(saver.save())
        assertArrayEquals("the shared save isn't rolled back", now, save.readBytes())
        // The game saves (from the loaded state on): that is written.
        sram = ByteArray(8) { 1 }.also { it[0] = 9 }
        assertTrue(saver.save())
        assertArrayEquals(sram, save.readBytes())
    }

    @Test
    fun `after a state is loaded the keep still guards a save the core didn't take as it was`() {
        val save = temp.root.resolve("saves/game.srm")
        val original = ByteArray(16) { 3 }
        save.parentFile!!.mkdirs()
        save.writeBytes(original)
        var sram = ByteArray(8) { 0 }
        val saver = SramSaver(save, loaded = { true }, read = { sram }, last = original.copyOf())
        saver.stateLoaded()
        sram = ByteArray(8) { 4 }
        assertTrue(saver.save())
        assertArrayEquals(original, temp.root.resolve("saves/game.srm" + SramSaver.KEEP_SUFFIX).readBytes())
    }
}
