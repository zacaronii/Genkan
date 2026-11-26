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

#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

namespace libretrodroid {

/**
 * Each retro_run's time and each step's count since the last take, so the player can say how
 * fast the game runs and whether the core itself is the cost (the wait for the next frame isn't
 * in it); and the sound the core made, which says it too for a core whose emulation isn't in its
 * runs. Recorded on the emulation thread, taken from any.
 */
class FrameStats {
public:
    static FrameStats& getInstance() {
        static FrameStats instance;
        return instance;
    }

    /** The rate the core runs at (its timing.fps), set as a game loads. */
    void setContentFps(double fps);

    /** The core's sound rate (its timing.sample_rate), set as a game loads. */
    void setSampleRate(double rate);

    /** [frames] of sound the core made, stereo frames. */
    void recordAudio(int64_t frames);

    /** A step whose run was held back, the core's sound being ahead (audiopace.h): on time, not slow. */
    void recordPaced();

    /** A read of the sound buffer that found too little and played silence for the rest; from the sound's thread. */
    void recordDry() { dry.fetch_add(1, std::memory_order_relaxed); }

    /** One retro_run that took [nanos]. */
    void recordRun(int64_t nanos);

    /** One step of the frame loop: [runs] retro_runs, then the frame shown. */
    void recordStep();

    /** The retro_runs of the last step together, in ns: one frame's work, for a performance hint. */
    int64_t lastStepNanos() const { return lastStep.load(std::memory_order_relaxed); }

    /**
     * Since the last take: runs, their total, longest and 95th-percentile time (ns), steps, the
     * content rate in thousandths of a frame a second, steps held back, sound frames made, and the
     * sound rate in thousandths of a frame a second, and the sound buffer's dry reads. Starts the next
     * period.
     */
    std::vector<int64_t> take();

private:
    FrameStats() = default;

    std::mutex lock;
    std::vector<int64_t> runs;
    int64_t steps = 0;
    int64_t contentFpsMilli = 0;
    int64_t sampleRateMilli = 0;
    int64_t paced = 0;
    int64_t audioFrames = 0;
    int64_t stepNanos = 0;
    std::atomic<int64_t> lastStep{0};
    std::atomic<int64_t> dry{0};
};

}

#endif //LIBRETRODROID_FRAMESTATS_H
