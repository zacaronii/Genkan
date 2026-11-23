package app.genkan.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveStatesTest {
    @get:Rule val temp = TemporaryFolder()

    private val root get() = File(temp.root, "states")
    private fun states(core: String = "snes9x", game: String = "7") = SaveStates(SaveStates.dirFor(root, core, game), core)

    @Test
    fun `a saved state reads back as it was, with when it was saved`() {
        val states = states()
        assertEquals(SaveStates.Read.Empty, states.read(1))
        assertNull(states.savedAt(1))
        states.write(1, byteArrayOf(1, 2, 3))
        val read = states.read(1) as SaveStates.Read.State
        assertArrayEquals(byteArrayOf(1, 2, 3), read.bytes)
        assertEquals(states.savedAt(1), read.savedAt)
    }

    @Test
    fun `saving into a used slot keeps what it held once, as its bak`() {
        val states = states()
        states.write(2, byteArrayOf(1))
        states.write(2, byteArrayOf(2))
        states.write(2, byteArrayOf(3))
        assertArrayEquals(byteArrayOf(3), (states.read(2) as SaveStates.Read.State).bytes)
        val baks = SaveStates.dirFor(root, "snes9x", "7").listFiles()!!.filter { it.name.endsWith(".bak") }
        assertEquals(listOf("slot2.state.gz.bak"), baks.map { it.name })
        // The bak is the state before the last save.
        val bak = SaveStates(baks.single().parentFile!!, "snes9x").apply { baks.single().copyTo(file(4)) }
        assertArrayEquals(byteArrayOf(2), (bak.read(4) as SaveStates.Read.State).bytes)
    }

    @Test
    fun `the state before a load is one file, not kept twice`() {
        val states = states()
        states.write(SaveStates.BEFORE_LOAD, byteArrayOf(1))
        states.write(SaveStates.BEFORE_LOAD, byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), (states.read(SaveStates.BEFORE_LOAD) as SaveStates.Read.State).bytes)
        assertEquals(1, SaveStates.dirFor(root, "snes9x", "7").listFiles()!!.size)
    }

    @Test
    fun `a state from another core is refused before it reaches this one`() {
        states(core = "mgba").write(1, byteArrayOf(1, 2))
        // The same file found under another core's folder (copied there).
        val mine = states()
        states(core = "mgba").file(1).copyTo(mine.file(1).apply { parentFile!!.mkdirs() })
        assertEquals(SaveStates.Read.OtherCore("mgba"), mine.read(1))
    }

    @Test
    fun `a cut-short file and one that isn't a state are unreadable, not loaded`() {
        val states = states()
        states.write(1, ByteArray(4096) { it.toByte() })
        val whole = states.file(1).readBytes()
        states.file(1).writeBytes(whole.copyOf(whole.size / 2))
        assertTrue(states.read(1) is SaveStates.Read.Unreadable)
        states.file(3).writeBytes(byteArrayOf(1, 2, 3, 4))
        assertTrue(states.read(3) is SaveStates.Read.Unreadable)
        java.util.zip.GZIPOutputStream(states.file(4).outputStream()).use { it.write("not a state at all".toByteArray()) }
        assertTrue(states.read(4) is SaveStates.Read.Unreadable)
    }

    @Test
    fun `a removed game's states go, whichever core wrote them, and no other game's`() {
        states(core = "snes9x", game = "7").write(1, byteArrayOf(1))
        states(core = "mgba", game = "7").write(1, byteArrayOf(1))
        states(core = "mgba", game = "8").write(1, byteArrayOf(1))
        UnpackedGames(File(temp.root, "unpacked"), root).forget(7)
        assertFalse(SaveStates.dirFor(root, "snes9x", "7").exists())
        assertFalse(SaveStates.dirFor(root, "mgba", "7").exists())
        assertTrue(SaveStates.dirFor(root, "mgba", "8").exists())
    }
}
