/*
 *     Genkan's additions to LibretroDroid: how long the core takes over its frames.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

#include "framestats.h"

#include <algorithm>
#include <cmath>
#include <numeric>

namespace libretrodroid {

void FrameStats::setContentFps(double fps) {
    std::lock_guard<std::mutex> guard(lock);
    contentFpsMilli = std::llround(fps * 1000.0);
}

void FrameStats::recordRun(int64_t nanos) {
    std::lock_guard<std::mutex> guard(lock);
    // A second's worth with fast-forward to spare; a period nobody takes doesn't grow without end.
    if (runs.size() < 4096) runs.push_back(nanos);
}

void FrameStats::recordStep() {
    std::lock_guard<std::mutex> guard(lock);
    steps++;
}

std::vector<int64_t> FrameStats::take() {
    std::vector<int64_t> period;
    int64_t taken;
    int64_t fps;
    {
        std::lock_guard<std::mutex> guard(lock);
        period.swap(runs);
        taken = steps;
        steps = 0;
        fps = contentFpsMilli;
    }
    int64_t total = std::accumulate(period.begin(), period.end(), int64_t{0});
    int64_t longest = period.empty() ? 0 : *std::max_element(period.begin(), period.end());
    int64_t p95 = 0;
    if (!period.empty()) {
        size_t at = std::min(period.size() - 1, static_cast<size_t>(std::ceil(period.size() * 0.95)) - 1);
        std::nth_element(period.begin(), period.begin() + at, period.end());
        p95 = period[at];
    }
    return {static_cast<int64_t>(period.size()), total, longest, p95, taken, fps};
}

}
