// What the built-in player's process offers the reader: the running game's memory and frames.
package app.genkan.core;

interface ICoreSession {
    /** The game's memory as (start, length, mirror 0/1) triples, at the addresses the game uses. */
    long[] regions() = 1;

    /** size bytes at address, or null when no region holds them all. */
    byte[] read(long address, int size) = 2;

    /** The next frame shown: "width", "height" and RGBA "pixels" (a SharedMemory), or null. */
    Bundle frame() = 3;

    /** Saves and closes the game; the process ends after. */
    oneway void quit() = 4;

    /** The size of the core's video RAM, 0 when it gives none. */
    long videoSize() = 5;

    /** size bytes of video RAM from offset, or null. */
    byte[] readVideo(long offset, int size) = 6;
}
