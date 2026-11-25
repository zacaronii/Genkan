package app.genkan.core

/**
 * When a script that refused the game is tried again. A script checks the game's code in memory as
 * it loads, and some consoles have that code only after their BIOS has run (a PS1), so "another
 * build" at the start may only mean "not yet": it's tried once a second through the game's first
 * minute, and through a minute after each savestate load. Only a script that still refuses then is
 * not this game's. Any other refusal (a later runtime, a region the host lacks, a syntax error) is
 * final.
 */
object ScriptRetry {
    const val EVERY_MS = 1_000L
    const val WINDOW_MS = 60_000L

    fun anotherBuild(error: String): Boolean = "another build" in error

    /**
     * Whether to try again after [error] at [nowMs], the game having started at [startedMs] and a
     * savestate last loaded at [stateLoadedMs] (null: none since).
     */
    fun again(error: String, nowMs: Long, startedMs: Long, stateLoadedMs: Long?): Boolean =
        anotherBuild(error) && (nowMs - startedMs < WINDOW_MS || (stateLoadedMs != null && nowMs - stateLoadedMs < WINDOW_MS))
}
