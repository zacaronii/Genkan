package app.genkan.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** GL reads a frame bottom row first; a Bitmap, and a kept frame, want it top row first. */
object TopDown {
    fun of(bottomUp: ByteBuffer, width: Int, height: Int): ByteBuffer {
        val row = width * 4
        val out = ByteBuffer.allocateDirect(row * height).order(ByteOrder.nativeOrder())
        val line = ByteArray(row)
        for (y in 0 until height) {
            bottomUp.position((height - 1 - y) * row)
            bottomUp.get(line)
            out.put(line)
        }
        out.rewind()
        return out
    }
}
