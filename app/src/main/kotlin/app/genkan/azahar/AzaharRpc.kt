package app.genkan.azahar

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A process the emulator runs: the game is the one whose title id is an application's. */
data class AzaharProcess(val pid: Int, val titleId: Long, val name: String) {
    /** Applications are 0004000000xxxxxx; system modules and applets sit in other ranges. */
    val isGame: Boolean get() = (titleId ushr 32) == 0x00040000L
}

/**
 * Azahar's scripting RPC: UDP requests of a 16-byte header (version, id, type, size) and two
 * u32 arguments, each answered with the same header and the data. Memory can only be read
 * whole, at most [MAX_READ] bytes a request; there are no breakpoints.
 */
class AzaharRpc(private val port: Int = PORT, timeoutMs: Int = 500) : Closeable {

    private val socket = DatagramSocket().apply { soTimeout = timeoutMs }
    // Azahar listens on IPv4 only, and Android's loopback address is IPv6.
    private val host = InetAddress.getByName("127.0.0.1")
    private var nextId = 1
    private val reply = ByteArray(HEADER + MAX_READ)

    /** The emulator's processes; empty when nothing answers (no game, or RPC off). */
    fun processes(): List<AzaharProcess> {
        val data = request(TYPE_PROCESS_LIST, 0, MAX_PROCESSES) ?: return emptyList()
        return parseProcesses(data)
    }

    /** Reads after this go to [pid]'s address space. */
    fun select(pid: Int): Boolean = request(TYPE_SET_GET_PROCESS, 1, pid) != null

    /** [size] bytes at [address], or null when the emulator didn't answer. */
    fun read(address: Long, size: Int): ByteArray? {
        require(size in 1..MAX_READ)
        val data = request(TYPE_READ_MEMORY, address.toInt(), size) ?: return null
        return data.takeIf { it.size == size }
    }

    @Synchronized
    private fun request(type: Int, arg1: Int, arg2: Int): ByteArray? {
        val id = nextId++
        val out = encode(id, type, arg1, arg2)
        socket.send(DatagramPacket(out, out.size, host, port))
        val packet = DatagramPacket(reply, reply.size)
        // Drop late answers to earlier requests that timed out.
        while (true) {
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                return null
            }
            if (packet.length < HEADER) continue
            val header = ByteBuffer.wrap(reply, 0, HEADER).order(ByteOrder.LITTLE_ENDIAN)
            header.int
            if (header.int != id) continue
            header.int
            val size = header.int.coerceIn(0, packet.length - HEADER)
            return reply.copyOfRange(HEADER, HEADER + size)
        }
    }

    override fun close() = socket.close()

    companion object {
        const val PORT = 45987
        const val MAX_READ = 32 * 1024
        const val VERSION = 2
        const val HEADER = 16
        const val TYPE_READ_MEMORY = 1
        const val TYPE_PROCESS_LIST = 3
        const val TYPE_SET_GET_PROCESS = 4
        private const val MAX_PROCESSES = 64
        private const val PROCESS_INFO = 0x14

        internal fun encode(id: Int, type: Int, arg1: Int, arg2: Int): ByteArray =
            ByteBuffer.allocate(HEADER + 8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(VERSION).putInt(id).putInt(type).putInt(8).putInt(arg1).putInt(arg2).array()

        /** A count, then packed (pid u32, title id u64, name 8 bytes) entries. */
        internal fun parseProcesses(data: ByteArray): List<AzaharProcess> {
            if (data.size < 4) return emptyList()
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.int.coerceAtMost((data.size - 4) / PROCESS_INFO)
            return List(count) {
                val pid = buffer.int
                val titleId = buffer.long
                val name = ByteArray(8).also(buffer::get).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
                AzaharProcess(pid, titleId, name)
            }
        }
    }
}
