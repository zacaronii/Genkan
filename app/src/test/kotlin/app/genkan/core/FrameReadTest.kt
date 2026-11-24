package app.genkan.core

import android.opengl.GLES30
import com.swordfish.libretrodroid.DefaultRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class FrameReadTest {

    /** GL state as a hardware-rendered core leaves it: its own framebuffer, pack buffer and row length. */
    private class FakeGl : DefaultRead.Gl {
        val ints = mutableMapOf(
            GLES30.GL_READ_FRAMEBUFFER_BINDING to 7,
            GLES30.GL_PIXEL_PACK_BUFFER_BINDING to 3,
            GLES30.GL_PACK_ALIGNMENT to 1,
            GLES30.GL_PACK_ROW_LENGTH to 640,
            GLES30.GL_PACK_SKIP_ROWS to 2,
            GLES30.GL_PACK_SKIP_PIXELS to 5,
        )
        override fun getInt(name: Int) = ints.getValue(name)
        override fun bindReadFramebuffer(id: Int) { ints[GLES30.GL_READ_FRAMEBUFFER_BINDING] = id }
        override fun bindPackBuffer(id: Int) { ints[GLES30.GL_PIXEL_PACK_BUFFER_BINDING] = id }
        override fun pixelStore(name: Int, value: Int) { ints[name] = value }
    }

    @Test
    fun `the frame is read from the screen with default packing, and the core's state put back`() {
        val gl = FakeGl()
        val before = gl.ints.toMap()
        val during = DefaultRead.read(gl) { gl.ints.toMap() }
        assertEquals(0, during[GLES30.GL_READ_FRAMEBUFFER_BINDING])
        assertEquals(0, during[GLES30.GL_PIXEL_PACK_BUFFER_BINDING])
        assertEquals(4, during[GLES30.GL_PACK_ALIGNMENT])
        assertEquals(0, during[GLES30.GL_PACK_ROW_LENGTH])
        assertEquals(0, during[GLES30.GL_PACK_SKIP_ROWS])
        assertEquals(0, during[GLES30.GL_PACK_SKIP_PIXELS])
        assertEquals(before, gl.ints)
    }

    @Test
    fun `the core's state is put back when the read fails`() {
        val gl = FakeGl()
        val before = gl.ints.toMap()
        val failed = runCatching { DefaultRead.read(gl) { error("no frame") } }
        assertTrue(failed.isFailure)
        assertEquals(before, gl.ints)
    }

    @Test
    fun `GL's bottom-up rows come out top row first`() {
        // 1 pixel wide, 3 high: GL gives the bottom row (3) first.
        val bottomUp = ByteBuffer.wrap(byteArrayOf(3, 3, 3, 3, 2, 2, 2, 2, 1, 1, 1, 1))
        val top = TopDown.of(bottomUp, width = 1, height = 3)
        val out = ByteArray(12).also { top.get(it) }
        assertEquals(listOf<Byte>(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3), out.toList())
    }
}
