// The reader's side of the built-in player, called from the player's process.
package app.genkan.core;

import app.genkan.core.ICoreSession;

interface ICoreHost {
    /** A game started in the player; the session lasts until the player's process ends. */
    void attach(ICoreSession session, long romId, String coreId, String look) = 1;

    /** Mono 16-bit little-endian samples the game just played, the newest at endMs (wall clock). */
    oneway void audio(in byte[] pcm, int sampleRate, long endMs) = 2;

    /** What the watched layout reads now (LayoutWatch's JSON), sent when it changes; blank: it stopped. */
    oneway void layout(String readJson) = 3;

    /** A tap on the overlay: [text] is the line tapped, [offset] the character in it. */
    oneway void overlayTap(String text, int offset) = 4;
    /** Whether the playing game has a manual, which the stick click can bring to the bottom screen. */
    boolean hasManual() = 5;
    /** Whether the manual is open over the reader on the bottom screen. */
    boolean manualOpen() = 6;
    /** Opens the manual over the reader (fetched first when it isn't cached), or closes it. */
    oneway void setManual(boolean open) = 7;
    /** A Back in the player: closes what is open on the bottom screen first; whether it did. */
    boolean bottomBack() = 8;
}
