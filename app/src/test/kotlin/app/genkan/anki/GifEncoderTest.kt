package app.genkan.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import kotlin.random.Random

class GifEncoderTest {

    private fun frame(width: Int, height: Int, pixel: (Int, Int) -> Int) =
        GifEncoder.Frame(IntArray(width * height) { i -> pixel(i % width, i / width) or (0xFF shl 24) }, width, height)

    /** The GIF's frames as the JDK decodes them: each one's pixels and where it sits. */
    private fun decode(gif: ByteArray): List<Triple<java.awt.image.BufferedImage, Int, Int>> {
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(gif))
        return (0 until reader.getNumImages(true)).map { i ->
            val root = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
            val descriptor = root.getElementsByTagName("ImageDescriptor").item(0) as IIOMetadataNode
            Triple(reader.read(i), descriptor.getAttribute("imageLeftPosition").toInt(), descriptor.getAttribute("imageTopPosition").toInt())
        }
    }

    @Test
    fun `a few colours come back exactly, and a later frame holds only what changed`() {
        val colors = intArrayOf(0x000000, 0xFFFFFF, 0xFF0000, 0x0000FF)
        val first = frame(40, 30) { x, y -> colors[(x / 10 + y / 10) % 4] }
        // The second differs only in a 5×3 box at (12, 20).
        val second = GifEncoder.Frame(first.argb.copyOf().also { p -> for (y in 20 until 23) for (x in 12 until 17) p[y * 40 + x] = 0xFFFFFFFF.toInt() }, 40, 30)
        val decoded = decode(GifEncoder.encode(listOf(first, second), delayMs = 100))
        assertEquals(2, decoded.size)
        val (image, _, _) = decoded[0]
        for (y in 0 until 30) for (x in 0 until 40) assertEquals(first.argb[y * 40 + x] and 0xFFFFFF, image.getRGB(x, y) and 0xFFFFFF)
        val (patch, left, top) = decoded[1]
        assertEquals(listOf(5, 3, 12, 20), listOf(patch.width, patch.height, left, top))
    }

    @Test
    fun `noise past the LZW table's 4096 codes still decodes`() {
        val random = Random(7)
        val noise = frame(200, 150) { _, _ -> random.nextInt(0x1000000) }
        val decoded = decode(GifEncoder.encode(listOf(noise), delayMs = 100))
        val image = decoded.single().first
        assertEquals(200, image.width)
        assertEquals(150, image.height)
        // 256 colours for many more: each pixel close to its own.
        var far = 0
        for (y in 0 until 150) for (x in 0 until 200) {
            val a = noise.argb[y * 200 + x]
            val b = image.getRGB(x, y)
            val d = listOf(16, 8, 0).sumOf { s -> Math.abs((a shr s and 0xFF) - (b shr s and 0xFF)) }
            if (d > 150) far++
        }
        assertTrue("far pixels: $far", far < 200 * 150 / 10)
    }

    @Test
    fun `an unchanged frame is a single pixel, and nothing is written for no frames`() {
        val still = frame(16, 16) { x, _ -> if (x < 8) 0x101010 else 0xF0F0F0 }
        val decoded = decode(GifEncoder.encode(listOf(still, still, still), delayMs = 100))
        assertEquals(3, decoded.size)
        assertEquals(1, decoded[1].first.width)
        assertEquals(0, GifEncoder.encode(emptyList(), delayMs = 100).size)
    }
}
