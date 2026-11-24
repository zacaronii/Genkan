package app.genkan.anki

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * A looping GIF of a few seconds of the game for a card's Picture, as cards mined from video
 * carry. One palette for the whole clip (median cut over its colours, 5 bits a channel), so a
 * frame changes only where the game did, and each frame after the first holds only the rectangle
 * that changed since the one before: a still scene with a text box typing out stays small.
 */
object GifEncoder {

    /** One frame: [argb] row by row, top row first ([width] × [height]). */
    class Frame(val argb: IntArray, val width: Int, val height: Int)

    /** Writes [frames] (all one size) to [out], each shown [delayMs]; nothing for no frames. */
    fun encode(frames: List<Frame>, delayMs: Int, out: OutputStream) {
        if (frames.isEmpty()) return
        val width = frames[0].width
        val height = frames[0].height
        require(frames.all { it.width == width && it.height == height && it.argb.size == width * height }) { "frames differ in size" }
        val palette = palette(frames)
        val lookup = lookup(palette)
        val indexed = frames.map { f -> ByteArray(f.argb.size) { i -> lookup[rgb555(f.argb[i])] } }

        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(out, width)
        short(out, height)
        // A global colour table of 256 entries, 8 bits a channel.
        out.write(0xF7)
        out.write(0)
        out.write(0)
        for (c in 0 until 256) {
            val color = palette.getOrElse(c) { 0 }
            out.write(color shr 16 and 0xFF)
            out.write(color shr 8 and 0xFF)
            out.write(color and 0xFF)
        }
        // Loops forever (the NETSCAPE2.0 extension).
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B) + "NETSCAPE2.0".toByteArray(Charsets.US_ASCII) + byteArrayOf(3, 1, 0, 0, 0))

        val delay = (delayMs + 5) / 10
        for (i in indexed.indices) {
            val rect = if (i == 0) Rect(0, 0, width, height) else changed(indexed[i - 1], indexed[i], width, height) ?: Rect(0, 0, 1, 1)
            // Graphic control: kept under the next frame (disposal 1), no transparency.
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04))
            short(out, delay)
            out.write(byteArrayOf(0, 0))
            out.write(0x2C)
            short(out, rect.left)
            short(out, rect.top)
            short(out, rect.width)
            short(out, rect.height)
            out.write(0)
            lzw(crop(indexed[i], width, rect), out)
        }
        out.write(0x3B)
    }

    fun encode(frames: List<Frame>, delayMs: Int): ByteArray = ByteArrayOutputStream().also { encode(frames, delayMs, it) }.toByteArray()

    private class Rect(val left: Int, val top: Int, val width: Int, val height: Int)

    /** The smallest rectangle holding every pixel that differs; null when none does. */
    private fun changed(before: ByteArray, after: ByteArray, width: Int, height: Int): Rect? {
        var top = -1
        var bottom = -1
        var left = width
        var right = -1
        for (y in 0 until height) {
            val row = y * width
            var first = -1
            var last = -1
            for (x in 0 until width) {
                if (before[row + x] != after[row + x]) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first >= 0) {
                if (top < 0) top = y
                bottom = y
                if (first < left) left = first
                if (last > right) right = last
            }
        }
        if (top < 0) return null
        return Rect(left, top, right - left + 1, bottom - top + 1)
    }

    private fun crop(indexed: ByteArray, width: Int, rect: Rect): ByteArray {
        if (rect.left == 0 && rect.top == 0 && rect.width == width && rect.width * rect.height == indexed.size) return indexed
        val out = ByteArray(rect.width * rect.height)
        for (y in 0 until rect.height) System.arraycopy(indexed, (rect.top + y) * width + rect.left, out, y * rect.width, rect.width)
        return out
    }

    private fun rgb555(argb: Int): Int = (argb shr 9 and 0x7C00) or (argb shr 6 and 0x3E0) or (argb shr 3 and 0x1F)

    /** Up to 256 colours (0xRRGGBB) for [frames]: median cut over their 15-bit colours, by count. */
    fun palette(frames: List<Frame>): IntArray {
        val counts = IntArray(32768)
        for (f in frames) for (p in f.argb) counts[rgb555(p)]++
        val colors = (0 until 32768).filter { counts[it] > 0 }.toIntArray()
        if (colors.size <= 256) return IntArray(colors.size) { expand(colors[it]) }
        val boxes = mutableListOf(colors)
        while (boxes.size < 256) {
            // The box with the most pixels in it is cut next, at its widest channel's median.
            val index = boxes.indices.filter { boxes[it].size > 1 }.maxByOrNull { i -> boxes[i].sumOf { counts[it].toLong() } } ?: break
            val box = boxes.removeAt(index)
            val shift = widestChannel(box)
            val sorted = box.sortedBy { it shr shift and 0x1F }.toIntArray()
            val total = sorted.sumOf { counts[it].toLong() }
            var running = 0L
            var cut = 1
            for (i in sorted.indices) {
                running += counts[sorted[i]]
                if (running * 2 >= total) {
                    cut = (i + 1).coerceIn(1, sorted.size - 1)
                    break
                }
            }
            boxes += sorted.copyOfRange(0, cut)
            boxes += sorted.copyOfRange(cut, sorted.size)
        }
        return IntArray(boxes.size) { i ->
            val box = boxes[i]
            val weight = box.sumOf { counts[it].toLong() }.coerceAtLeast(1)
            fun mean(shift: Int) = (box.sumOf { (it shr shift and 0x1F).toLong() * counts[it] } * 255 / (31 * weight)).toInt()
            (mean(10) shl 16) or (mean(5) shl 8) or mean(0)
        }
    }

    private fun widestChannel(box: IntArray): Int = listOf(10, 5, 0).maxBy { shift ->
        box.maxOf { it shr shift and 0x1F } - box.minOf { it shr shift and 0x1F }
    }

    private fun expand(c555: Int): Int {
        fun ch(v: Int) = v shl 3 or (v shr 2)
        return (ch(c555 shr 10 and 0x1F) shl 16) or (ch(c555 shr 5 and 0x1F) shl 8) or ch(c555 and 0x1F)
    }

    /** Each 15-bit colour's nearest palette entry. */
    private fun lookup(palette: IntArray): ByteArray = ByteArray(32768) { c ->
        val color = expand(c)
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (i in palette.indices) {
            val dr = (palette[i] shr 16 and 0xFF) - (color shr 16 and 0xFF)
            val dg = (palette[i] shr 8 and 0xFF) - (color shr 8 and 0xFF)
            val db = (palette[i] and 0xFF) - (color and 0xFF)
            val distance = dr * dr * 2 + dg * dg * 4 + db * db * 3
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        best.toByte()
    }

    private fun short(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write(value shr 8 and 0xFF)
    }

    /** GIF's LZW, 8-bit pixels, in data sub-blocks. */
    private fun lzw(pixels: ByteArray, out: OutputStream) {
        val minCodeSize = 8
        out.write(minCodeSize)
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val blocks = SubBlocks(out)
        var codeSize = minCodeSize + 1
        var next = end + 1
        val table = HashMap<Int, Int>(8192)
        blocks.code(clear, codeSize)
        if (pixels.isEmpty()) {
            blocks.code(end, codeSize)
            blocks.finish()
            return
        }
        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until pixels.size) {
            val k = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val found = table[key]
            if (found != null) {
                prefix = found
                continue
            }
            blocks.code(prefix, codeSize)
            if (next < 4096) {
                table[key] = next++
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                blocks.code(clear, codeSize)
                table.clear()
                codeSize = minCodeSize + 1
                next = end + 1
            }
            prefix = k
        }
        blocks.code(prefix, codeSize)
        blocks.code(end, codeSize)
        blocks.finish()
    }

    /** Packs codes least-significant bit first into blocks of at most 255 bytes, then the terminator. */
    private class SubBlocks(private val out: OutputStream) {
        private val block = ByteArray(255)
        private var size = 0
        private var bits = 0
        private var count = 0

        fun code(code: Int, width: Int) {
            bits = bits or (code shl count)
            count += width
            while (count >= 8) {
                byte(bits and 0xFF)
                bits = bits ushr 8
                count -= 8
            }
        }

        private fun byte(b: Int) {
            block[size++] = b.toByte()
            if (size == 255) flush()
        }

        private fun flush() {
            if (size == 0) return
            out.write(size)
            out.write(block, 0, size)
            size = 0
        }

        fun finish() {
            if (count > 0) byte(bits and 0xFF)
            bits = 0
            count = 0
            flush()
            out.write(0)
        }
    }
}
