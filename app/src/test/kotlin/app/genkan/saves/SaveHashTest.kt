package app.genkan.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class SaveHashTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a plain file hashes as its bytes`() {
        val file = tmp.newFile("game.srm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(md5(byteArrayOf(1, 2, 3)), SaveHash.ofFile(file))
        assertEquals(md5(byteArrayOf(1, 2, 3)), SaveHash.ofUpload(file))
    }

    @Test
    fun `a folder hashes like RomM hashes its zip`() {
        val folder = tmp.newFolder("data")
        File(folder, "00000001").mkdirs()
        File(folder, "00000001/userdata").writeText("save")
        File(folder, "00000001.metadata").writeText("meta")
        // RomM: md5 of "name:md5" lines, sorted by name.
        val expected = md5("00000001.metadata:${md5("meta".toByteArray())}\n00000001/userdata:${md5("save".toByteArray())}".toByteArray())
        assertEquals(expected, SaveHash.ofFolder(folder))
        val zip = File(tmp.root, "save.zip")
        SaveHash.zipFolder(folder, zip)
        assertEquals(expected, SaveHash.ofZip(zip))
        assertEquals(expected, SaveHash.ofUpload(zip))
    }

    @Test
    fun `a 3DS title id comes from the NCSD media id`() {
        val header = ByteArray(0x110)
        "NCSD".toByteArray().copyInto(header, 0x100)
        byteArrayOf(0x00, 0x80.toByte(), 0x1b, 0x00, 0x00, 0x00, 0x04, 0x00).copyInto(header, 0x108)
        assertEquals("00040000001b8000", ThreeDs.titleId(header))
        assertNull(ThreeDs.titleId(ByteArray(0x110)))
    }

    @Test
    fun `saves are found where each emulator keeps them`() {
        File(tmp.root, "Azahar/sdmc").mkdirs()
        val locator = SaveLocator(tmp.root)
        val rom = tmp.newFile("Game (Japan).sfc")
        val srm = locator.locate("snes", rom)!!
        assertEquals(File(tmp.root, "RetroArch/saves/Snes9x/Game (Japan).srm"), srm.path)
        assertEquals("snes9x", srm.emulator)
        val cart = tmp.newFile("Game.3ds").apply {
            writeBytes(ByteArray(0x110).also { h ->
                "NCSD".toByteArray().copyInto(h, 0x100)
                byteArrayOf(0x00, 0x80.toByte(), 0x1b, 0x00, 0x00, 0x00, 0x04, 0x00).copyInto(h, 0x108)
            })
        }
        val azahar = locator.locate("3ds", cart)!!
        assertEquals(
            File(tmp.root, "Azahar/sdmc/Nintendo 3DS/00000000000000000000000000000000/00000000000000000000000000000000/title/00040000/001b8000/data"),
            azahar.path,
        )
        assertEquals("00040000001b8000.zip", azahar.name)
        assertNull(locator.locate("psx", rom))
    }
}
