// Genkan: one sector of a CHD's data track (discsector.cpp), on a made-up disc (fixtures/make_chd.py):
// an audio track of 2 frames, then a MODE1 data track of 3 whose sector 1 is a PC Engine CD boot block.
#include <cstdio>
#include <cstring>

#include "discsector.h"

using libretrodroid::chdDataSector;

namespace {

int failures = 0;

void check(bool ok, const char *what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        failures++;
    }
}

}

int main() {
    auto boot = chdDataSector(DISC_FIXTURE, 1);
    check(boot.size() == 2048, "a data sector is its 2048 bytes of user data");
    check(boot.size() == 2048 && std::memcmp(boot.data() + 0x20, "PC Engine CD-ROM SYSTEM\0", 24) == 0,
          "sector 1 of the data track, past the audio track and its padding: the boot block");
    auto first = chdDataSector(DISC_FIXTURE, 0);
    check(first.size() == 2048 && first[0] == 0x11 && first[2047] == 0x11, "sector 0 is the track's first, without its sync and header");
    auto third = chdDataSector(DISC_FIXTURE, 2);
    check(third.size() == 2048 && third[0] == 0x33, "sector 2 is its last");
    check(chdDataSector(DISC_FIXTURE, 3).empty(), "none past the track's end (its padding isn't the track)");
    check(chdDataSector("/nonexistent/disc.chd", 1).empty(), "no file, no sector");
    check(chdDataSector(__FILE__, 1).empty(), "a file that isn't a CHD gives none");

    if (failures == 0) std::printf("disc sector: passed\n");
    return failures == 0 ? 0 : 1;
}
