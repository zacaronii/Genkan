// Genkan: where a touch leaves a core's pointer (pointer.h): with a finger down, lifted, and touching
// outside the picture, for a pointer that follows the finger off and one that stays (a Wii's).
#include <cstdio>

#include "pointer.h"

using libretrodroid::PointerState;

namespace {

int failures = 0;

void check(bool ok, const char *what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        failures++;
    }
}

bool at(const PointerState &p, float x, float y) {
    return p.x == x && p.y == y;
}

}

int main() {
    PointerState fresh;
    check(!fresh.pressed && fresh.x < 0 && fresh.y < 0, "before any touch it's off the screen");

    PointerState follows;
    follows.touch(0.25f, 0.75f, false);
    check(follows.pressed && at(follows, 0.25f, 0.75f), "a finger down puts it there, pressed");
    follows.touch(-1, -1, false);
    check(!follows.pressed && at(follows, -1, -1), "lifted, it goes off the screen with the finger");

    PointerState stays;
    stays.touch(-1, -1, true);
    check(!stays.pressed && at(stays, -1, -1), "kept, it's still off the screen before any touch");
    stays.touch(0.25f, 0.75f, true);
    stays.touch(0.5f, 0.4f, true);
    check(stays.pressed && at(stays, 0.5f, 0.4f), "kept, it follows the finger while it's down");
    stays.touch(-1, -1, true);
    check(!stays.pressed && at(stays, 0.5f, 0.4f), "kept, lifted, it stays where the finger was, not pressed");
    stays.touch(0.1f, 0.9f, true);
    check(stays.pressed && at(stays, 0.1f, 0.9f), "a new touch moves it again");
    stays.touch(0.3f, -1, true);
    check(!stays.pressed && at(stays, 0.1f, 0.9f), "a touch outside the picture leaves it too");

    if (failures == 0) std::printf("pointer: passed\n");
    return failures == 0 ? 0 : 1;
}
