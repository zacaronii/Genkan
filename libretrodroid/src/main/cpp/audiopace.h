/*
 *     Genkan's additions to LibretroDroid: holding back a core that makes sound faster than it plays.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

#ifndef LIBRETRODROID_AUDIOPACE_H
#define LIBRETRODROID_AUDIOPACE_H

namespace libretrodroid {

/**
 * Whether the frame loop runs the core this step, by how far its sound is ahead of the frames asked
 * of it. The loop asks for a frame a display frame and runs one retro_run for it, which keeps a core
 * at speed when one run is one of its frames; a core whose emulation runs on a thread of its own
 * (Flycast) can make more than a frame's worth of game in a run (two, for a game drawing 30 frames a
 * second), and plays fast. Its sound says how much game it made: [run] keeps the sound made against
 * the frames asked, in frames, and at [HIGH] ahead the core is held back a step at a time until it's
 * down to [LOW] (two marks, so a core near the line doesn't stutter), never more than [MAX_HELD] steps
 * in a row, so the picture keeps coming. A core whose run is a frame stays within a frame of even,
 * however its sound comes, and is never held; one that falls behind isn't owed more than [BEHIND]
 * frames, so a slow stretch doesn't let it run fast after.
 *
 * Counted in frames, not by how full the sound buffer is: the buffer holds a few frames, and its
 * reader changes its own speed to keep it half full, so its level says little about one run.
 */
class AudioPace {
public:
    static constexpr double HIGH = 2.0;
    static constexpr double LOW = 1.0;
    static constexpr double BEHIND = 4.0;
    static constexpr int MAX_HELD = 3;

    /**
     * True to run the core this step. [made] is the sound the core made since the last step, in its
     * frames (sound frames over its sound frames a frame); [asked] the frames the loop asks this step.
     */
    bool run(double made, unsigned asked) {
        ahead += made - asked;
        if (ahead < -BEHIND) ahead = -BEHIND;
        if (ahead >= HIGH) {
            holding = true;
        } else if (ahead <= LOW) {
            holding = false;
        }
        if (!holding || held >= MAX_HELD) {
            held = 0;
            return true;
        }
        held++;
        return false;
    }

    void reset() {
        ahead = 0;
        holding = false;
        held = 0;
    }

private:
    double ahead = 0;
    bool holding = false;
    int held = 0;
};

/**
 * Whether the frame loop draws the screen itself after a step. A core drawn in its own video callback
 * (one rendering into a framebuffer, Flycast's) is drawn there, but a step held back above gives no
 * callback, nor does a run that sends no frame: then the last frame is drawn again, or the display
 * shows a buffer nobody drew in (a 30 fps game held every other step flashed with it).
 */
inline bool drawsAfterStep(bool rendersInVideoCallback, bool videoCallbackThisStep) {
    return !rendersInVideoCallback || !videoCallbackThisStep;
}

}

#endif //LIBRETRODROID_AUDIOPACE_H
