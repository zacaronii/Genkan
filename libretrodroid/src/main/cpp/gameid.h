// Genkan: the game a core names through Genkan's private environment call (a PS2 core's disc serial
// and ELF CRC, as patches/lrps2 makes LRPS2 hand them over), kept as PINE reports the same pair.
#ifndef LIBRETRODROID_GAMEID_H
#define LIBRETRODROID_GAMEID_H

#include <cstdio>
#include <string>

namespace libretrodroid {

/** The call's number: one of libretro's private ones. */
constexpr unsigned GENKAN_ENVIRONMENT_SET_GAME_ID = 0x20000 | 0x4701;

/** What the call hands over. */
struct GenkanGameId {
    const char *serial;
    unsigned crc;
};

/**
 * "SLPM-12345 1a2b3c4d": a disc's serial (four capital letters, a dash, five digits) and its ELF's
 * CRC as 8 lower-case hex digits. Empty for anything else, the BIOS's own "serial" among them.
 */
inline std::string formatGameId(const char *serial, unsigned crc) {
    if (serial == nullptr) return {};
    std::string s(serial);
    if (s.size() != 10 || s[4] != '-') return {};
    for (int i = 0; i < 4; i++) if (s[i] < 'A' || s[i] > 'Z') return {};
    for (int i = 5; i < 10; i++) if (s[i] < '0' || s[i] > '9') return {};
    char hex[9];
    std::snprintf(hex, sizeof(hex), "%08x", crc);
    return s + " " + hex;
}

}

#endif
