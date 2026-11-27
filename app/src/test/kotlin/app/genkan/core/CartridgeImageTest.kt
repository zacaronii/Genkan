package app.genkan.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CartridgeImageTest {
    @get:Rule val folder = TemporaryFolder()

    private val prgChr = ByteArray(24 * 1024) { (it % 251).toByte() }
    private fun ines(flags6: Int) = byteArrayOf(0x4E, 0x45, 0x53, 0x1A, 1, 1, flags6.toByte()) + ByteArray(9)

    @Test
    fun `a Game Boy's file whole, a Famicom's past its header and trainer, a disk past its fwNES header`() {
        val gb = ByteArray(32 * 1024) { (it % 7).toByte() }
        assertArrayEquals(gb, CartridgeImage.of("gb", gb))
        assertArrayEquals(gb, CartridgeImage.of("gbc", gb))
        assertArrayEquals(prgChr, CartridgeImage.of("nes", ines(0) + prgChr))
        assertArrayEquals(prgChr, CartridgeImage.of("famicom", ines(0x04) + ByteArray(512) { 0x77 } + prgChr))
        // A file with no header is the program and CHR already.
        assertArrayEquals(prgChr, CartridgeImage.of("nes", prgChr))
        val sides = ByteArray(2 * 65500) { (it % 13).toByte() }
        val fwnes = byteArrayOf(0x46, 0x44, 0x53, 0x1A, 2) + ByteArray(11)
        assertArrayEquals(sides, CartridgeImage.of("fds", fwnes + sides))
        assertArrayEquals(sides, CartridgeImage.of("fds", sides))
    }

    @Test
    fun `none for another console, an empty image, or a file that isn't there`() {
        assertNull(CartridgeImage.of("snes", prgChr))
        assertNull(CartridgeImage.of("nes", ines(0)))
        assertNull(CartridgeImage.of("gb", File(folder.root, "missing.gb")))
        val nes = File(folder.root, "game.nes").apply { writeBytes(ines(0) + prgChr) }
        assertArrayEquals(prgChr, CartridgeImage.of("nes", nes))
    }
}
