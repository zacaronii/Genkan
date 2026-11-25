// Genkan: the part of a hardware core's framebuffer its frame filled: smaller, the same, larger, unknown.
#include <cmath>
#include <cstdio>

#include "framefill.h"

using libretrodroid::filledFraction;

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

    if (failures == 0) std::printf("frame fill: passed\n");
    return failures == 0 ? 0 : 1;
}
