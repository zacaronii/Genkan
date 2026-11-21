// The reader's side of the built-in player, called from the player's process.
package app.genkan.core;

import app.genkan.core.ICoreSession;

interface ICoreHost {
    /** A game started in the player; the session lasts until the player's process ends. */
    void attach(ICoreSession session, long romId, String coreId) = 1;

    /** Mono 16-bit little-endian samples the game just played, the newest at endMs (wall clock). */
    oneway void audio(in byte[] pcm, int sampleRate, long endMs) = 2;

    /** What the watched layout reads now (LayoutWatch's JSON), sent when it changes; blank: it stopped. */
    oneway void layout(String readJson) = 3;
}
