package app.genkan.script

import app.genkan.memory.MemorySource

/**
 * Yomikata's script host in Genkan's own process (libgenkan_script: the built-in player's
 * scripthost.cpp and reader runtime), for a game running in another emulator. One script at a
 * time. The game's memory is read through the [MemorySource] each call is given.
 */
object AppScriptHost {
    init {
        System.loadLibrary("genkan_script")
    }

    /** Loads a script; null when it loaded, else why not. It checks the game's memory as it loads. */
    fun load(
        name: String,
        source: ByteArray,
        data: Map<String, ByteArray>,
        platform: String,
        gameId: String,
        buildId: String?,
        pointerMask: Long,
        ramBase: Long,
        memory: MemorySource,
    ): String? {
        val names = data.keys.toTypedArray()
        val files = names.map { data.getValue(it) }.toTypedArray()
        return loadNative(name, source, names, files, platform, gameId, buildId, pointerMask, ramBase, memory)?.toString(Charsets.UTF_8)
    }

    /** Runs the script for [frame] (frames the game has run): its events, each a kind (ScriptEvents) and JSON. */
    fun frame(frame: Long, memory: MemorySource): List<Pair<Int, String>> =
        frameNative(frame, memory)?.map { bytes ->
            val line = bytes.toString(Charsets.UTF_8)
            line.substringBefore('\t').toInt() to line.substringAfter('\t')
        }.orEmpty()

    /** The script feature level the runtime gives (yk_script_level). */
    external fun level(): Int

    external fun unload()

    /** After a savestate was loaded, before the next [frame]. */
    external fun stateLoaded()

    private external fun loadNative(
        name: String, source: ByteArray, dataNames: Array<String>, dataFiles: Array<ByteArray>,
        platform: String, gameId: String, buildId: String?, pointerMask: Long, ramBase: Long, memory: MemorySource,
    ): ByteArray?

    private external fun frameNative(frame: Long, memory: MemorySource): Array<ByteArray>?
}
