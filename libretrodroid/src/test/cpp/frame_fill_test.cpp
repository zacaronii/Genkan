// Genkan: the part of a hardware core's framebuffer its frame filled: smaller, the same, larger, unknown;
// and the size the framebuffer is made.
#include <cmath>
#include <cstdio>
#include <utility>

#include "framefill.h"

using libretrodroid::filledFraction;
using libretrodroid::framebufferSize;

namespace {

int failures = 0;

void check(bool ok, const char *what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        failures++;
    }
}

bool near(float a, float b) { return std::fabs(a - b) < 1e-6F; }

}

int main() {
    auto smaller = filledFraction(512, 448, 640, 480);
    check(near(smaller.first, 0.8F) && near(smaller.second, 448.0F / 480.0F), "a smaller frame fills its part");

    auto same = filledFraction(640, 480, 640, 480);
    check(near(same.first, 1.0F) && near(same.second, 1.0F), "a frame the framebuffer's size fills all of it");

    auto larger = filledFraction(1280, 960, 640, 480);
    check(near(larger.first, 1.0F) && near(larger.second, 1.0F), "a larger frame is held to all of it");

    auto unknown = filledFraction(0, 0, 640, 480);
    check(near(unknown.first, 1.0F) && near(unknown.second, 1.0F), "no frame yet counts as all of it");
    auto noTexture = filledFraction(512, 448, 0, 0);
    check(near(noTexture.first, 1.0F) && near(noTexture.second, 1.0F), "no framebuffer size counts as all of it");

    using Size = std::pair<unsigned, unsigned>;
    check(framebufferSize(640, 480, 0, 0) == Size(640, 480), "before any frame, the size asked for");
    check(framebufferSize(640, 480, 640, 480) == Size(640, 480), "a core drawing at its base size keeps it");
    check(framebufferSize(640, 480, 512, 448) == Size(640, 480), "and one drawing under it");
    check(framebufferSize(640, 480, 1920, 1440) == Size(1920, 1440), "a frame past it grows the framebuffer to the frame");
    check(framebufferSize(640, 480, 1920, 400) == Size(1920, 480), "each side on its own");
    check(framebufferSize(320, 240, 1920, 1440) == Size(1920, 1440), "a smaller size asked for later doesn't shrink it under a frame drawn");
    check(framebufferSize(2560, 1920, 1920, 1440) == Size(2560, 1920), "a larger one grows it");

    if (failures == 0) std::printf("frame fill: passed\n");
    return failures == 0 ? 0 : 1;
}
