package app.genkan.armsx2

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What ARMSX2 says it's doing. */
enum class PineStatus { RUNNING, PAUSED, SHUTDOWN }

/** The game ARMSX2 is running, as PINE gives it: [serial] and [crc] name its build. */
data class PineGame(val serial: String, val crc: String, val title: String, val version: String)

/**
 * PINE, the IPC PCSX2 serves and ARMSX2 (2.9.6 on) serves on Android: TCP on 127.0.0.1, port
 * [PORT] unless its `EmuCore/PINESlot` says another. A message is its whole size (u32,
 * little-endian, counting itself) then commands, each an opcode and its arguments; the answer is
 * its size, a result (0, or 0xFF when any command failed) and each command's value in order.
 * Many reads go in one message, up to [MAX_READS].
 */
object Pine {
    const val PORT = 28011
    /** Reads in one message: ARMSX2 answers at most 450,000 bytes, 50,000 eight-byte reads and a margin. */
    const val MAX_READS = 50_000

    const val READ8 = 0x00
    const val READ64 = 0x03
    const val VERSION = 0x08
    const val TITLE = 0x0B
    const val ID = 0x0C
    const val UUID = 0x0D
    const val GAME_VERSION = 0x0E
    const val STATUS = 0x0F

    private const val OK = 0x00

    /** A message of [commands]: each an opcode and, for a read, its address. */
    fun message(commands: List<Command>): ByteArray {
        val size = 4 + commands.sumOf { if (it.address != null) 5 else 1 }
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).putInt(size)
        for (command in commands) {
            out.put(command.opcode.toByte())
            command.address?.let { out.putInt(it.toInt()) }
        }
        return out.array()
    }

    /** One command: [opcode], and the [address] a read is at. */
    data class Command(val opcode: Int, val address: Long? = null)

    /**
     * The values in [answer] (the bytes after its size) for [commands]: a read's bytes as they lie
     * in memory, a text's UTF-8 bytes, a status's four; null when ARMSX2 said it failed.
     */
    fun values(commands: List<Command>, answer: ByteArray): List<ByteArray>? {
        if (answer.isEmpty() || answer[0].toInt() and 0xFF != OK) return null
        val buffer = ByteBuffer.wrap(answer, 1, answer.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        return commands.map { command ->
            val size = when (command.opcode) {
                READ8 -> 1
                READ64 -> 8
                STATUS -> 4
                VERSION, TITLE, ID, UUID, GAME_VERSION -> buffer.int
                else -> throw IOException("no reply size known for opcode ${command.opcode}")
            }
            if (size < 0 || size > buffer.remaining()) throw IOException("a reply runs past the message")
            ByteArray(size).also { buffer.get(it) }
        }
    }

    /** A text value without its closing NUL. */
    fun text(value: ByteArray): String = value.decodeToString().trimEnd('\u0000')
}

/**
 * A connection to ARMSX2's PINE. It connects when first asked and again after a failure, so it
 * outlives ARMSX2 restarting; every call is null (or empty) while nothing answers.
 */
class PineClient(private val port: Int = Pine.PORT, private val timeoutMs: Int = 1_000) : Closeable {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null

    /** Round trips made, for the cost of following a game. */
    var roundTrips = 0L
        private set

    fun status(): PineStatus? = ask(listOf(Pine.Command(Pine.STATUS)))?.single()?.let {
        when (ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int) {
            0 -> PineStatus.RUNNING
            1 -> PineStatus.PAUSED
            else -> PineStatus.SHUTDOWN
        }
    }

    /** The game running, or null when there's none (ARMSX2 fails these without one). */
    fun game(): PineGame? {
        val values = ask(listOf(Pine.ID, Pine.UUID, Pine.TITLE, Pine.GAME_VERSION).map { Pine.Command(it) }) ?: return null
        val (serial, crc, title, version) = values.map(Pine::text)
        return PineGame(serial, crc.lowercase(), title, version)
    }

    fun version(): String? = ask(listOf(Pine.Command(Pine.VERSION)))?.single()?.let(Pine::text)

    /** [size] bytes at [address] as they lie in memory, eight at a time where they can be; null when not read. */
    fun read(address: Long, size: Int): ByteArray? {
        if (size <= 0) return ByteArray(0)
        val commands = ArrayList<Pine.Command>()
        var at = address
        val end = address + size
        while (at < end) {
            if (at % 8 == 0L && end - at >= 8) {
                commands += Pine.Command(Pine.READ64, at)
                at += 8
            } else {
                commands += Pine.Command(Pine.READ8, at)
                at++
            }
        }
        val out = ByteArray(size)
        var filled = 0
        for (batch in commands.chunked(Pine.MAX_READS)) {
            val values = ask(batch) ?: return null
            for (value in values) {
                value.copyInto(out, filled)
                filled += value.size
            }
        }
        return out
    }

    /** One message's values, or null: ARMSX2 failed it, or didn't answer. */
    @Synchronized
    fun ask(commands: List<Pine.Command>): List<ByteArray>? {
        repeat(2) { attempt ->
            try {
                val (input, output) = connected()
                output.write(Pine.message(commands))
                output.flush()
                val answer = readAnswer(input)
                roundTrips++
                return Pine.values(commands, answer)
            } catch (e: IOException) {
                // A connection ARMSX2 closed (it restarted, or the game did) gets one fresh try.
                disconnect()
                if (attempt == 1) return null
            }
        }
        return null
    }

    private fun readAnswer(input: InputStream): ByteArray {
        val data = DataInputStream(input)
        val header = ByteArray(4).also(data::readFully)
        val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
        if (size < 5 || size > MAX_ANSWER) throw IOException("an answer of $size bytes")
        return ByteArray(size - 4).also(data::readFully)
    }

    private fun connected(): Pair<InputStream, OutputStream> {
        val input = input
        val output = output
        if (input != null && output != null) return input to output
        // ARMSX2 listens on IPv4 loopback only.
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.soTimeout = timeoutMs
        socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        this.socket = socket
        return DataInputStream(socket.getInputStream().buffered()).also { this.input = it } to
            socket.getOutputStream().buffered().also { this.output = it }
    }

    private fun disconnect() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    @Synchronized
    override fun close() = disconnect()

    private companion object {
        /** ARMSX2's own limit on an answer. */
        const val MAX_ANSWER = 450_000
    }
}
