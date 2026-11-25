/*
 *     Genkan's additions to LibretroDroid: how long the core takes over its frames.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

#ifndef LIBRETRODROID_FRAMESTATS_H
#define LIBRETRODROID_FRAMESTATS_H

#include <cstdint>
#include <mutex>
#include <vector>

namespace libretrodroid {

/**
 * Each retro_run's time and each step's count since the last take, so the player can say how
 * fast the game runs and whether the core itself is the cost (the wait for the next frame isn't
 * in it). Recorded on the emulation thread, taken from any.
 */
class FrameStats {
public:
    static FrameStats& getInstance() {
        static FrameStats instance;
        return instance;
    }

    /** The rate the core runs at (its timing.fps), set as a game loads. */
    void setContentFps(double fps);

    /** One retro_run that took [nanos]. */
    void recordRun(int64_t nanos);

    /** One step of the frame loop: [runs] retro_runs, then the frame shown. */
    void recordStep();

    /**
     * Since the last take: runs, their total, longest and 95th-percentile time (ns), steps, and
     * the content rate in thousandths of a frame a second. Starts the next period.
     */
    std::vector<int64_t> take();

private:
    FrameStats() = default;

    std::mutex lock;
    std::vector<int64_t> runs;
    int64_t steps = 0;
    int64_t contentFpsMilli = 0;
};

}

#endif //LIBRETRODROID_FRAMESTATS_H
