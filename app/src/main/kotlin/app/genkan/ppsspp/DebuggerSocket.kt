package app.genkan.ppsspp

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The smallest WebSocket client PPSSPP's debugger needs: text frames only, each written in one
 * go. PPSSPP 1.20's server stalls when a frame arrives in pieces, as OkHttp sends them (header
 * and payload apart), and the stalled connection then holds up every other one.
 */
class DebuggerSocket private constructor(private val socket: Socket) : Closeable {

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output: OutputStream = socket.getOutputStream()
    private val random = SecureRandom()

    /** Sends one text frame, masked as a client must, in a single write. */
    @Synchronized
    fun send(text: String) {
        output.write(encode(text.toByteArray(), random))
        output.flush()
    }

    /** The next text message; null when the server closed. Answers pings, skips other frames. */
    fun receive(): String? {
        val message = java.io.ByteArrayOutputStream()
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = input.readUnsignedByte()
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) length = input.readUnsignedShort().toLong()
            else if (length == 127L) length = input.readLong()
            val mask = if (b1 and 0x80 != 0) ByteArray(4).also(input::readFully) else null
            val payload = ByteArray(length.toInt()).also(input::readFully)
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (b0 and 0x0F) {
                0x8 -> return null
                0x9 -> synchronized(this) { output.write(frame(0xA, payload, random)); output.flush() }
                0x1, 0x0 -> {
                    message.write(payload)
                    if (b0 and 0x80 != 0) return message.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    override fun close() = socket.close()

    companion object {
        /** Opens ws://[host]:[port][path] with PPSSPP's subprotocol; throws when it refuses. */
        fun connect(host: String, port: Int, path: String, timeoutMs: Int): DebuggerSocket {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                // PPSSPP can take a few seconds to answer the upgrade.
                socket.soTimeout = timeoutMs
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
                val request = "GET $path HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: debugger.ppsspp.org\r\n\r\n"
                socket.getOutputStream().apply { write(request.toByteArray()); flush() }
                val status = readHeaders(socket)
                if (!status.first.contains(" 101")) throw IOException("upgrade refused: ${status.first}")
                if (status.second["sec-websocket-accept"] != acceptFor(key)) throw IOException("bad Sec-WebSocket-Accept")
                socket.soTimeout = 0
                return DebuggerSocket(socket)
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }

        /** A masked client frame of [payload] with [opcode], final. */
        internal fun frame(opcode: Int, payload: ByteArray, random: SecureRandom): ByteArray {
            val header = java.io.ByteArrayOutputStream()
            header.write(0x80 or opcode)
            when {
                payload.size < 126 -> header.write(0x80 or payload.size)
                payload.size < 65536 -> { header.write(0x80 or 126); header.write(payload.size shr 8); header.write(payload.size and 0xFF) }
                else -> { header.write(0x80 or 127); for (i in 7 downTo 0) header.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
            }
            val mask = ByteArray(4).also(random::nextBytes)
            header.write(mask)
            val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            return header.toByteArray() + masked
        }

        internal fun encode(text: ByteArray, random: SecureRandom) = frame(0x1, text, random)

        internal fun acceptFor(key: String): String = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
        )

        /** The status line and lower-cased headers, read byte by byte so no frame data is taken. */
        private fun readHeaders(socket: Socket): Pair<String, Map<String, String>> {
            val stream = socket.getInputStream()
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            while (true) {
                val c = stream.read()
                if (c < 0) throw IOException("closed during handshake")
                if (c == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    line.clear()
                    if (text.isEmpty()) break
                    lines += text
                } else {
                    line.append(c.toChar())
                }
            }
            val headers = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            return (lines.firstOrNull() ?: "") to headers
        }
    }
}
