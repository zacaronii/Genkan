package app.genkan.ppsspp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom

class DebuggerSocketTest {

    @Test
    fun `the accept key follows RFC 6455`() {
        // The RFC's own example.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", DebuggerSocket.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun `client frames are masked and carry their length`() {
        val payload = "hello".toByteArray()
        val frame = DebuggerSocket.encode(payload, SecureRandom())
        assertEquals(0x81, frame[0].toInt() and 0xFF)
        assertEquals(0x80 or 5, frame[1].toInt() and 0xFF)
        val mask = frame.copyOfRange(2, 6)
        val unmasked = ByteArray(5) { (frame[6 + it].toInt() xor mask[it % 4].toInt()).toByte() }
        assertArrayEquals(payload, unmasked)
        val long = DebuggerSocket.encode(ByteArray(300), SecureRandom())
        assertEquals(0x80 or 126, long[1].toInt() and 0xFF)
        assertEquals(300, ((long[2].toInt() and 0xFF) shl 8) or (long[3].toInt() and 0xFF))
    }
}
