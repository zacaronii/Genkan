/*
 *     Genkan's additions to LibretroDroid: what the reader needs from a running core.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

#ifndef LIBRETRODROID_GENKANTAP_H
#define LIBRETRODROID_GENKANTAP_H

#include <cstdint>
#include <mutex>
#include <vector>

#include "../../libretro-common/include/libretro.h"

namespace libretrodroid {

/**
 * The game's memory, as the core describes it (its memory map, or else its system RAM), for
 * reads from another thread; and the game's sound, folded to mono, for clips. Both are guarded
 * so a read never touches memory of a core that was just unloaded.
 */
class GenkanTap {
public:
    struct Region {
        uint64_t start;
        uint64_t length;
        uint8_t *host;
        // Another address for memory an earlier region already covers (a mirror).
        bool mirror;
    };

    static GenkanTap& getInstance() {
        static GenkanTap instance;
        return instance;
    }
    GenkanTap(GenkanTap const&) = delete;
    void operator=(GenkanTap const&) = delete;

    void setMemoryMaps(const struct retro_memory_map *map);
    void addSystemRam(void *data, size_t size);
    void clear();
    std::vector<Region> regions();
    bool read(uint64_t address, uint8_t *out, size_t size);

    void setSampleRate(int32_t rate);
    int32_t getSampleRate();
    void pushAudio(const int16_t *stereo, size_t frames);
    size_t drainAudio(int16_t *out, size_t capacity);

private:
    GenkanTap() = default;

    std::mutex memoryLock;
    std::vector<Region> memory;

    std::mutex audioLock;
    std::vector<int16_t> ring;
    size_t ringStart = 0;
    size_t ringCount = 0;
    int32_t sampleRate = 0;
};

}

#endif //LIBRETRODROID_GENKANTAP_H
