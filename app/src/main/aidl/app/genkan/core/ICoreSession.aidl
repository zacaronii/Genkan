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

    /** The core's savestate (retro_serialize), gzipped into the file at path; its size before gzip, or -1. */
    long saveStateTo(String path) = 7;

    /** The next frame at the core's own size (a PS1's 320x240), as frame() gives it, or null. */
    Bundle nativeFrame() = 8;

    /**
     * Reads a Yomikata layout (its JSON) after every frame, or at its poll rate, and tells the
     * host each time what it reads changes. [entryJson] carries what the entry adds to its text
     * (unit, controls, cleanup), [tableText] its table. An empty layout stops it. Whether the
     * player took the layout.
     */
    boolean watchLayout(String layoutJson, String tableText, String entryJson) = 9;

    /** The lines to box over the game (ScreenText's JSON); blank or "[]": none. */
    oneway void showOverlay(String screenTextJson) = 10;

    /**
     * One moment of the game, whole: the next frame is kept, and before another runs, length
     * bytes of memory from address go into the file at path (in the snapshot folder; what can't
     * be read stays zeros). Whether it was taken; keptFrame() then gives that frame.
     */
    boolean captureTo(String path, long address, int length) = 11;

    /** The frame the last captureTo kept, at the core's own size, as frame() gives it, or null. */
    Bundle keptFrame() = 12;
    /** Draws the game with the ScreenLook named [look] from now on (this play only). */
    oneway void setLook(String look) = 13;
    /** A list to draw over the game, apart from the backlog (OverlayPanel's JSON): a choice's answers; blank: none. */
    oneway void showPanel(String panelJson) = 14;
}
