// Genkan: the game a core names through Genkan's private call (gameid.h), kept as PINE reports it.
// Made-up serials and CRCs.
#include <cstdio>
#include <string>

#include "gameid.h"

using libretrodroid::formatGameId;

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
    check(formatGameId("SLPM-12345", 0x1A2B3C4Du) == "SLPM-12345 1a2b3c4d", "a disc's serial and its CRC, lower-case hex");
    check(formatGameId("SCPS-10001", 0xABCDu) == "SCPS-10001 0000abcd", "the CRC keeps its 8 digits");
    check(formatGameId("20991231-120000", 0x12345678u).empty(), "a BIOS-style date serial isn't a game");
    check(formatGameId("", 0x12345678u).empty(), "no serial, no game");
    check(formatGameId(nullptr, 0x12345678u).empty(), "nor a missing one");
    check(formatGameId("slpm-12345", 0x1u).empty(), "lower-case letters aren't a serial");
    check(formatGameId("SLPM_123.45", 0x1u).empty(), "nor the boot path's form");
    check(formatGameId("SLPM-1234", 0x1u).empty() && formatGameId("SLPM-123456", 0x1u).empty(), "five digits, no more or fewer");
    check(libretrodroid::GENKAN_ENVIRONMENT_SET_GAME_ID == (0x20000u | 0x4701u), "the call's number, as patches/lrps2 makes it");

    if (failures == 0) std::printf("game id: passed\n");
    return failures == 0 ? 0 : 1;
}
