package com.swordfish.libretrodroid

import android.opengl.GLES30

/**
 * Genkan: a read of the frame on screen, with the default framebuffer and pixel-store state, and
 * whatever was bound put back after. The draw hands a hardware-rendered core (Dolphin) its own GL
 * state back, so a read straight after would read the core's framebuffer, into its pack buffer,
 * with its row length: a black frame, or a small one smeared across rows.
 */
object DefaultRead {
    /** The GL calls a read needs, so the state handling can be tested without a context. */
    interface Gl {
        fun getInt(name: Int): Int
        fun bindReadFramebuffer(id: Int)
        fun bindPackBuffer(id: Int)
        fun pixelStore(name: Int, value: Int)
    }

    /** The pixel-store state a read uses, and its defaults (alignment 4, everything else 0). */
    val PACK_DEFAULTS = listOf(
        GLES30.GL_PACK_ALIGNMENT to 4,
        GLES30.GL_PACK_ROW_LENGTH to 0,
        GLES30.GL_PACK_SKIP_ROWS to 0,
        GLES30.GL_PACK_SKIP_PIXELS to 0,
    )

    fun <T> read(gl: Gl, block: () -> T): T {
        val framebuffer = gl.getInt(GLES30.GL_READ_FRAMEBUFFER_BINDING)
        val packBuffer = gl.getInt(GLES30.GL_PIXEL_PACK_BUFFER_BINDING)
        val store = PACK_DEFAULTS.map { (name, _) -> name to gl.getInt(name) }
        gl.bindReadFramebuffer(0)
        gl.bindPackBuffer(0)
        PACK_DEFAULTS.forEach { (name, value) -> gl.pixelStore(name, value) }
        try {
            return block()
        } finally {
            store.forEach { (name, value) -> gl.pixelStore(name, value) }
            gl.bindPackBuffer(packBuffer)
            gl.bindReadFramebuffer(framebuffer)
        }
    }

    /** The current context's GL. */
    val GLES = object : Gl {
        private val value = IntArray(1)
        override fun getInt(name: Int): Int {
            GLES30.glGetIntegerv(name, value, 0)
            return value[0]
        }
        override fun bindReadFramebuffer(id: Int) = GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, id)
        override fun bindPackBuffer(id: Int) = GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, id)
        override fun pixelStore(name: Int, value: Int) = GLES30.glPixelStorei(name, value)
    }
}
