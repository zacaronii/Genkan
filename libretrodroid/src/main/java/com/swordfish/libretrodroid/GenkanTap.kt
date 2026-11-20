/*
 *     Genkan's additions to LibretroDroid.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

package com.swordfish.libretrodroid

/** A stretch of the game's memory at its address as the game sees it. */
data class MemoryRegion(val start: Long, val length: Long, val mirror: Boolean) {
    val range: LongRange get() = start until start + length
}

/**
 * What the reader needs from the running core, safe to call from any thread: reads of the
 * game's memory, and the sound it made since the last drain, folded to mono.
 */
object GenkanTap {

    init {
        System.loadLibrary("libretrodroid")
    }

    /** The core's memory map, or its system RAM at address 0 when it gave none. */
    fun regions(): List<MemoryRegion> =
        memoryRegions().toList().chunked(3).map { (start, length, mirror) -> MemoryRegion(start, length, mirror != 0L) }

    /** [size] bytes at [address] into [out]; false when no region holds all of them. */
    @JvmStatic external fun readMemory(address: Long, out: ByteArray, size: Int): Boolean

    /** The core's sample rate, 0 before a game is loaded. */
    @JvmStatic external fun audioSampleRate(): Int

    /** Moves up to `out.size` mono samples into [out]; returns how many. */
    @JvmStatic external fun drainAudio(out: ShortArray): Int

    @JvmStatic private external fun memoryRegions(): LongArray
}
