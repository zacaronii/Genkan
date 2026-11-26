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

    /** Keeps the next frame the core draws at its own size ([frameSize], [copyFrame]). */
    @JvmStatic external fun requestFrame()

    /** Where the game's picture sits on the view (left, top, right, bottom fractions), or null before there's one. */
    @JvmStatic external fun pictureBounds(): FloatArray?

    /** The size of the core's video RAM, 0 when it gives none. */
    @JvmStatic external fun videoRamSize(): Long

    /** [size] bytes of video RAM from [offset] into [out]; false past its end. */
    @JvmStatic external fun readVideoRam(offset: Long, out: ByteArray, size: Int): Boolean

    /** The core's sample rate, 0 before a game is loaded. */
    @JvmStatic external fun audioSampleRate(): Int

    /** Moves up to `out.size` mono samples into [out]; returns how many. */
    @JvmStatic external fun drainAudio(out: ShortArray): Int

    /** Why the game can't be heard (its sound stream wouldn't open, or stopped), or null while it can. */
    @JvmStatic external fun audioProblem(): String?

    /** Forgets the last game's sound problem: the player's process can outlive a game. */
    @JvmStatic external fun clearAudioProblem()

    /**
     * The core stacks two screens in one frame (a DS): the view draws only the upper one, and the
     * whole frame is kept for [copyFrame]. Set before the view is made.
     */
    @JvmStatic external fun setSplitScreens(split: Boolean)

    /**
     * A lifted finger leaves the core's pointer where it was, no longer pressed (a Wii's: aimed,
     * then left there while a button is pressed); else it goes off the screen with the finger.
     */
    @JvmStatic external fun setKeepPointer(keep: Boolean)

    /** The last kept frame's width and height, or null before one came. */
    fun frameSize(): Pair<Int, Int>? {
        val packed = packedFrameSize()
        val width = (packed ushr 32).toInt()
        val height = packed.toInt()
        return if (width > 0 && height > 0) width to height else null
    }

    /** RGBA rows of the last kept frame into the direct [out]; false when there is no such frame. */
    @JvmStatic external fun copyFrame(out: java.nio.ByteBuffer, firstRow: Int, rows: Int): Boolean

    /** A touch on the lower screen, from 0 to 1 across and down it; negative for a release. */
    @JvmStatic external fun touchLowerScreen(x: Float, y: Float)

    /**
     * Loads a Yomikata script for the running game (docs/script-api.md there), with its data files
     * by name; null when it loaded, else why not. [platform] is the catalog's name ("gc"), and a game's
     * address folds as (address and [pointerMask]) - [ramBase] onto the core's. On the emulation thread.
     */
    fun scriptLoad(
        name: String,
        source: ByteArray,
        data: Map<String, ByteArray>,
        platform: String,
        gameId: String,
        buildId: String?,
        pointerMask: Long,
        ramBase: Long,
    ): String? {
        val names = data.keys.toTypedArray()
        val files = names.map { data.getValue(it) }.toTypedArray()
        return scriptLoadNative(name, source, names, files, platform, gameId, buildId, pointerMask, ramBase)?.toString(Charsets.UTF_8)
    }

    /**
     * Runs the loaded script for [frame] (frames the game has run): its events, each the runtime's
     * kind (1 text, 2 layout, 3 cursor, 4 log) and JSON. Between two frames, on the emulation thread.
     */
    fun scriptFrame(frame: Long): List<Pair<Int, String>> =
        scriptFrameNative(frame)?.map { bytes ->
            val line = bytes.toString(Charsets.UTF_8)
            line.substringBefore('\t').toInt() to line.substringAfter('\t')
        }.orEmpty()

    /** The script feature level the runtime gives (yk_script_level): a script needing more is left alone. */
    @JvmStatic external fun scriptLevel(): Int

    @JvmStatic external fun scriptUnload()
    @JvmStatic external fun scriptLoaded(): Boolean
    /** After a savestate was loaded, before the next [scriptFrame]. */
    @JvmStatic external fun scriptStateLoaded()
    /** The size of the game's picture in pixels, for the script's reader.picture(). */
    @JvmStatic external fun scriptPicture(width: Int, height: Int)

    @JvmStatic
    @JvmName("scriptLoad")
    private external fun scriptLoadNative(
        name: String, source: ByteArray, dataNames: Array<String>, dataFiles: Array<ByteArray>,
        platform: String, gameId: String, buildId: String?, pointerMask: Long, ramBase: Long,
    ): ByteArray?

    @JvmStatic
    @JvmName("scriptFrame")
    private external fun scriptFrameNative(frame: Long): Array<ByteArray>?

    /**
     * The core's frames since the last call, and a new period begun: retro_runs, their total,
     * longest and 95th-percentile time in nanoseconds, frame-loop steps, and the core's rate in
     * thousandths of a frame a second.
     */
    @JvmStatic external fun frameStats(): LongArray

    /** The last frame's retro_runs together, in nanoseconds: its work, for a performance hint. */
    @JvmStatic external fun lastStepNanos(): Long

    @JvmStatic private external fun memoryRegions(): LongArray

    @JvmStatic private external fun packedFrameSize(): Long
}
