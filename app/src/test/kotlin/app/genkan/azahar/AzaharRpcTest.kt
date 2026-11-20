package app.genkan.azahar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AzaharRpcTest {

    @Test
    fun `a request is the version 2 header and two arguments, little-endian`() {
        val bytes = ByteBuffer.wrap(AzaharRpc.encode(id = 9, type = AzaharRpc.TYPE_READ_MEMORY, arg1 = 0x30000000, arg2 = 512))
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf(2, 9, 1, 8, 0x30000000, 512), List(6) { bytes.int })
    }

    @Test
    fun `the process list is a count and packed entries`() {
        val data = ByteBuffer.allocate(4 + 0x14 * 2).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(2)
            .putInt(3).putLong(0x0004013000001502L).put("fs\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray())
            .putInt(11).putLong(0x00040000001B8000L).put("KanKen3D".toByteArray())
            .array()
        val processes = AzaharRpc.parseProcesses(data)
        assertEquals(listOf("fs", "KanKen3D"), processes.map { it.name })
        assertFalse(processes[0].isGame)
        assertTrue(processes[1].isGame)
        assertEquals(11, processes[1].pid)
    }
}
