package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Games handed to a core: as they are, or unpacked from a zip. All archives here are made up. */
class GameFileTest {
    @get:Rule val temp = TemporaryFolder()

    private val mgba = CoreCatalog.forPlatform("gba")!!
    private val melonds = CoreCatalog.forPlatform("nds")!!
    private val cache by lazy { temp.newFolder("unpacked") }

    /** A zip of [entries] (name to bytes) in the downloads folder. */
    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val file = File(temp.root, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((entry, bytes) in entries) {
                out.putNextEntry(ZipEntry(entry))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun bytes(size: Int, fill: Int) = ByteArray(size) { (it * 7 + fill).toByte() }

    @Test
    fun `a file the core takes is handed over as it is`() {
        val rom = File(temp.root, "game.gba").apply { writeBytes(bytes(64, 1)) }
        assertEquals(GameFile.Prepared.Ready(rom), GameFile.prepare(mgba, rom, cache, "1"))
        assertTrue(GameFile.takes(mgba, rom))
    }

    @Test
    fun `a zip with one game is unpacked, the zip left as it was`() {
        val game = bytes(4096, 2)
        val rom = zip("game.zip", "game.gba" to game)
        val before = rom.readBytes()
        val ready = GameFile.prepare(mgba, rom, cache, "1") as GameFile.Prepared.Ready
        assertEquals("game.gba", ready.file.name)
        assertTrue(ready.file.canonicalPath.startsWith(cache.canonicalPath))
        assertTrue(ready.file.readBytes().contentEquals(game))
        assertNull(ready.note)
        assertTrue(rom.readBytes().contentEquals(before))
    }

    @Test
    fun `an unchanged zip is unpacked once, then reused`() {
        val rom = zip("game.zip", "game.gb" to bytes(2048, 3))
        val first = (GameFile.prepare(mgba, rom, cache, "1") as GameFile.Prepared.Ready).file
        first.setLastModified(1_000_000L)
        val second = (GameFile.prepare(mgba, rom, cache, "1") as GameFile.Prepared.Ready).file
        assertEquals(first, second)
        assertEquals("not written again", 1_000_000L, second.lastModified())
    }

    @Test
    fun `of several the core takes, the first by name, folders included, and the note says so`() {
        val rom = zip(
            "set.zip",
            "readme.txt" to bytes(10, 4),
            "b side.gba" to bytes(512, 5),
            "Disk/a side.gba" to bytes(512, 6),
        )
        val ready = GameFile.prepare(mgba, rom, cache, "2") as GameFile.Prepared.Ready
        assertEquals("a side.gba", ready.file.name)
        assertTrue(ready.file.readBytes().contentEquals(bytes(512, 6)))
        assertEquals("Playing Disk/a side.gba, the first of 2 in the zip", ready.note)
    }

    @Test
    fun `a disc in several files is played through its cue sheet, with every track beside it`() {
        val psx = CoreCatalog.forPlatform("psx")!!
        val cue = "FILE \"disc (Track 1).bin\" BINARY\n  TRACK 01 MODE2/2352\nFILE \"disc (Track 2).bin\" BINARY\n  TRACK 02 AUDIO\n".toByteArray()
        val rom = zip(
            "disc.zip",
            "disc/disc (Track 1).bin" to bytes(2352, 19),
            "disc/disc (Track 2).bin" to bytes(2352, 20),
            "disc/disc.cue" to cue,
        )
        val ready = GameFile.prepare(psx, rom, cache, "8") as GameFile.Prepared.Ready
        assertEquals("disc.cue", ready.file.name)
        assertNull("one index: nothing to choose", ready.note)
        val folder = ready.file.parentFile!!
        assertTrue(File(folder, "disc (Track 1).bin").readBytes().contentEquals(bytes(2352, 19)))
        assertTrue("the audio track too", File(folder, "disc (Track 2).bin").readBytes().contentEquals(bytes(2352, 20)))
    }

    @Test
    fun `a playlist comes before the cue sheets it lists`() {
        val psx = CoreCatalog.forPlatform("psx")!!
        val rom = zip(
            "set.zip",
            "a disc 1.cue" to "FILE \"a disc 1.bin\" BINARY\n".toByteArray(),
            "a disc 1.bin" to bytes(2352, 21),
            "b disc 2.cue" to "FILE \"b disc 2.bin\" BINARY\n".toByteArray(),
            "b disc 2.bin" to bytes(2352, 22),
            "z set.m3u" to "a disc 1.cue\nb disc 2.cue\n".toByteArray(),
        )
        val ready = GameFile.prepare(psx, rom, cache, "9") as GameFile.Prepared.Ready
        assertEquals("z set.m3u", ready.file.name)
        assertTrue("both discs beside it", File(ready.file.parentFile, "b disc 2.bin").isFile)
    }

    @Test
    fun `a zip with nothing the core takes can't be played, and says why`() {
        val rom = zip("other.zip", "game.sfc" to bytes(512, 7), "notes.txt" to bytes(8, 8))
        assertFalse(GameFile.takes(mgba, rom))
        val result = GameFile.prepare(mgba, rom, cache, "3")
        assertEquals(GameFile.Prepared.Unplayable("The game's zip holds nothing mGBA can play"), result)
        assertTrue("nothing written", cache.listFiles().isNullOrEmpty())
    }

    @Test
    fun `an entry named to climb out of the folder is written inside it, by its own name`() {
        val rom = zip("hostile.zip", "../../outside.nds" to bytes(256, 9))
        val ready = GameFile.prepare(melonds, rom, cache, "4") as GameFile.Prepared.Ready
        assertEquals("outside.nds", ready.file.name)
        assertEquals(File(cache, ready.file.parentFile!!.name).canonicalPath, ready.file.parentFile!!.canonicalPath)
        assertFalse(File(temp.root, "outside.nds").exists())
    }

    @Test
    fun `a game larger than the cap isn't unpacked`() {
        val rom = zip("big.zip", "big.gba" to bytes(4096, 10))
        val result = GameFile.prepare(mgba, rom, cache, "5", maxBytes = 1024)
        assertTrue(result is GameFile.Prepared.Unplayable)
        assertTrue("nothing left behind", cache.listFiles().orEmpty().none { it.walk().any { f -> f.isFile } })
    }

    @Test
    fun `a changed zip is unpacked afresh, and the old copy removed`() {
        val rom = zip("game.zip", "game.gba" to bytes(1024, 11))
        val old = (GameFile.prepare(mgba, rom, cache, "6") as GameFile.Prepared.Ready).file
        // Replaced on the server, downloaded again: other bytes, another size.
        zip("game.zip", "game.gba" to bytes(2048, 12))
        rom.setLastModified(rom.lastModified() + 5_000)
        val fresh = (GameFile.prepare(mgba, rom, cache, "6") as GameFile.Prepared.Ready).file
        assertTrue(fresh.readBytes().contentEquals(bytes(2048, 12)))
        assertFalse(old.parentFile!!.exists())
    }

    @Test
    fun `removing a game removes only its unpacked files`() {
        GameFile.prepare(mgba, zip("a.zip", "a.gba" to bytes(64, 13)), cache, "7")
        GameFile.prepare(mgba, zip("b.zip", "b.gba" to bytes(64, 14)), cache, "70")
        UnpackedGames(cache).forget(7)
        assertEquals(listOf("70"), cache.listFiles()!!.map { it.name.substringBefore('-') })
    }

    @Test
    fun `routing - the built-in core plays its own files and zips holding them, nothing else`() {
        assertTrue(GameFile.takes(melonds, zip("ds.zip", "game.nds" to bytes(64, 15))))
        assertFalse(GameFile.takes(melonds, zip("gba.zip", "game.gba" to bytes(64, 16))))
        assertFalse(GameFile.takes(mgba, File(temp.root, "game.7z").apply { writeBytes(bytes(64, 17)) }))
        assertTrue(GameFile.takes(CoreCatalog.forPlatform("psx")!!, File(temp.root, "game.chd").apply { writeBytes(bytes(64, 18)) }))
        // A console with no built-in core goes to the outside emulator as before.
        assertNull(CoreCatalog.forPlatform("snes"))
    }
}
