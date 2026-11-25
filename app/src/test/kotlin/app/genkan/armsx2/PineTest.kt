package app.genkan.armsx2

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class PineTest {

    /**
     * ARMSX2's PINE as far as Genkan uses it, over a loopback socket: reads from [memory], the
     * game's facts, its status; a message fails whole when any command does (no game, or a read
     * outside memory). It answers in two writes, as TCP may deliver it, and can drop a connection.
     */
    private class FakePine(val memory: ByteArray) : AutoCloseable {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        @Volatile var game = true
        /** The message (counting from 1) it closes the connection on instead of answering. */
        @Volatile var drop = 0
        @Volatile var messages = 0
        @Volatile var connections = 0

        init {
            thread(isDaemon = true) {
                try {
                    while (true) {
                        val socket = server.accept()
                        connections++
                        thread(isDaemon = true) {
                            socket.use {
                                val input = DataInputStream(it.getInputStream())
                                val output = it.getOutputStream()
                                try {
                                    while (true) {
                                        val size = Integer.reverseBytes(input.readInt())
                                        val body = ByteArray(size - 4).also(input::readFully)
                                        if (++messages == drop) return@thread
                                        val answer = answer(body)
                                        output.write(answer, 0, 3)
                                        output.flush()
                                        output.write(answer, 3, answer.size - 3)
                                        output.flush()
                                    }
                                } catch (e: Exception) {
                                    // the client went away
                                }
                            }
                        }
                    }
                } catch (e: SocketException) {
                    // closed
                }
            }
        }

        private fun answer(body: ByteArray): ByteArray {
            val input = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            val out = ByteBuffer.allocate(1 shl 20).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(0).put(0)
            fun text(value: String) {
                val bytes = value.toByteArray() + 0
                out.putInt(bytes.size).put(bytes)
            }
            while (input.hasRemaining()) {
                when (input.get().toInt()) {
                    Pine.READ8, Pine.READ64 -> {
                        val wide = body[input.position() - 1].toInt() == Pine.READ64
                        val address = input.int.toLong() and 0xFFFFFFFFL
                        val size = if (wide) 8 else 1
                        if (!game || address + size > memory.size) return fail()
                        out.put(memory, address.toInt(), size)
                    }
                    Pine.STATUS -> out.putInt(if (game) 0 else 2)
                    Pine.ID -> if (game) text("SLPM-12345") else return fail()
                    Pine.UUID -> if (game) text("1A2B3C4D") else return fail()
                    Pine.TITLE -> if (game) text("A made-up game") else return fail()
                    Pine.GAME_VERSION -> if (game) text("1.01") else return fail()
                    else -> return fail()
                }
            }
            val size = out.position()
            out.putInt(0, size)
            return out.array().copyOf(size)
        }

        private fun fail() = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).putInt(5).put(0xFF.toByte()).array()

        override fun close() = server.close()
    }

    private val memory = ByteArray(1 shl 20) { (it * 7 + it / 256).toByte() }
    private val pine = FakePine(memory)
    private val client = PineClient(pine.server.localPort, timeoutMs = 2_000)

    @After
    fun close() {
        client.close()
        pine.close()
    }

    @Test
    fun `reads come back as they lie in memory, eight bytes a command where they can`() {
        // Starts and ends off an eight-byte boundary.
        assertArrayEquals(memory.copyOfRange(0x1003, 0x1003 + 29), client.read(0x1003, 29))
        assertEquals(1, pine.messages)
    }

    @Test
    fun `a read larger than one message's limit takes several`() {
        val size = Pine.MAX_READS * 8 + 100
        assertArrayEquals(memory.copyOfRange(0x40, 0x40 + size), client.read(0x40, size))
        assertEquals(2, pine.messages)
    }

    @Test
    fun `the game's facts and status`() {
        assertEquals(PineGame("SLPM-12345", "1a2b3c4d", "A made-up game", "1.01"), client.game())
        assertEquals("SLPM-12345-1a2b3c4d", client.game()?.buildId)
        assertEquals(PineStatus.RUNNING, client.status())
        pine.game = false
        assertNull(client.game())
        assertNull(client.read(0, 16))
        assertEquals(PineStatus.SHUTDOWN, client.status())
    }

    @Test
    fun `a dropped connection is made again`() {
        pine.drop = 2
        assertArrayEquals(memory.copyOfRange(0, 8), client.read(0, 8))
        // ARMSX2 closes the connection on the second: asked again on a new one.
        assertArrayEquals(memory.copyOfRange(8, 16), client.read(8, 8))
        assertEquals(2, pine.connections)
    }

    @Test
    fun `nothing listening is null, not an error`() {
        val port = pine.server.localPort
        pine.close()
        assertNull(PineClient(port, timeoutMs = 300).use { it.status() })
    }

    @Test
    fun `memory reads go a page at a time, kept until the next tick`() {
        val ram = PineMemory(client)
        assertArrayEquals(memory.copyOfRange(0x1FF0, 0x2010), ram.read(0x1FF0, 0x20))
        assertEquals(1, pine.messages) // both pages in one run
        assertArrayEquals(memory.copyOfRange(0x2100, 0x2108), ram.read(0x2100, 8))
        assertEquals(1, pine.messages) // already read this tick
        ram.nextTick()
        ram.read(0x2100, 8)
        assertEquals(2, pine.messages)
        // Outside the 32 MB, or more than one read gives: not asked.
        assertNull(ram.read(PineMemory.RAM_SIZE - 4, 8))
        assertNull(ram.read(0, PineMemory.MAX_READ + 1))
    }

    @Test
    fun `pointers through the EE's segments fold back to main memory`() {
        val ram = PineMemory(client)
        assertEquals(0x123456L, ram.target(0x00123456))
        assertEquals(0x123456L, ram.target(0x20123456))
        assertEquals(0x123456L, ram.target(0x80123456))
        assertNull(ram.target(0x12000000)) // hardware registers
        assertNull(ram.target(0x03000000)) // past 32 MB
    }
}
