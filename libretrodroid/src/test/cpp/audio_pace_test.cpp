// Genkan: when the frame loop holds a core back by its sound (audiopace.h).
#include <cstdio>

#include "audiopace.h"

using libretrodroid::AudioPace;
using libretrodroid::drawsAfterStep;

namespace {

int failures = 0;

void check(bool ok, const char *what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        failures++;
    }
}

// Steps of a core that makes [perRun] frames of sound a run, one frame asked a step: how many ran.
int runsOf(AudioPace &pace, double perRun, int steps) {
    int ran = 0;
    double made = 0;
    for (int i = 0; i < steps; i++) {
        bool runs = pace.run(made, 1);
        made = runs ? perRun : 0;
        ran += runs;
    }
    return ran;
}

}

int main() {
    AudioPace even;
    check(runsOf(even, 1.0, 600) == 600, "a core whose run is a frame is never held");

    AudioPace lumpy;
    double made[] = {0, 0, 3, 0, 1, 2, 0, 2};
    int ran = 0;
    for (int i = 0; i < 800; i++) ran += lumpy.run(made[i % 8], 1);
    check(ran == 800, "nor one whose sound comes in lumps that add up to a frame a step");

    AudioPace twice;
    int twiceRan = runsOf(twice, 2.0, 600);
    check(twiceRan >= 299 && twiceRan <= 301, "a core making two frames a run runs every other step");

    AudioPace fast;
    int fastRan = runsOf(fast, 5.0, 600);
    check(fastRan >= 150, "never more than three steps held in a row: the picture keeps coming");

    AudioPace marks;
    check(marks.run(0, 1) && marks.run(2.9, 1), "a core under two frames ahead runs");
    check(!marks.run(2.1, 1), "at two ahead it's held");
    check(!marks.run(0.5, 1), "and held while it's between the marks");
    check(marks.run(0, 1), "at a frame ahead it runs again");
    check(marks.run(1, 1) && marks.run(1.6, 1), "and keeps running until the high mark again");

    AudioPace slow;
    slow.run(0, 30);
    check(slow.run(5, 1), "a core that fell behind isn't held catching up");
    check(!slow.run(3, 1), "but is owed no more than four frames");

    AudioPace reset;
    reset.run(5, 1);
    reset.reset();
    check(reset.run(1, 1), "a reset forgets the hold");

    check(drawsAfterStep(false, false) && drawsAfterStep(false, true), "a core drawn by the loop is drawn every step");
    check(!drawsAfterStep(true, true), "a core drawn in its video callback isn't drawn twice");
    check(drawsAfterStep(true, false), "but is drawn again after a step that gave no frame (held back)");

    if (failures == 0) std::printf("audio pace: passed\n");
    return failures == 0 ? 0 : 1;
}
