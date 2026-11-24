package app.genkan.memory

import java.io.Closeable

/** A running game's memory, read from outside the game: an emulator's RPC, or the built-in player. */
interface MemorySource : Closeable {
    /** Where the game's data can be, to search for text. Unused stretches may read as zeros. */
    val regions: List<LongRange>

    /** The most one [read] returns. */
    val maxRead: Int

    /**
     * The address a pointer holding [value] points at, or null when it can't point into the
     * game's memory. Systems that see memory through mirrors (the PS1's 0x80000000) fold here.
     */
    fun target(value: Long): Long?

    /** Whether the game keeps its words big-endian (a GameCube's): its pointers, and UTF-16 text. */
    val bigEndian: Boolean get() = false

    /** [size] bytes at [address], or null when they can't be read. */
    fun read(address: Long, size: Int): ByteArray?
}
