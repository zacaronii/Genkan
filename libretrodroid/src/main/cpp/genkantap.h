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

#include <atomic>
#include <cstdint>
#include <utility>
#include <mutex>
#include <string>
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
    /**
     * Without a memory map, the core's system RAM, asked for again at each read: some cores
     * (PPSSPP) set their memory up only after the game has loaded.
     */
    void setSystemRam(void *(*data)(unsigned), size_t (*size)(unsigned));
    void clear();
    std::vector<Region> regions();
    bool read(uint64_t address, uint8_t *out, size_t size);
    /**
     * Up to `size` bytes at `address` from `among` (a copy of [regions] taken once a frame): how many
     * were there, fewer where the region ends, 0 outside every region. For the script's many small reads.
     */
    static size_t readClipped(const std::vector<Region> &among, uint64_t address, uint8_t *out, size_t size);
    /** The core's video RAM (a PS1's 1 MB frame buffer and texture pages), 0 when it gives none. */
    size_t videoRamSize();
    bool readVideoRam(uint64_t offset, uint8_t *out, size_t size);

    void setSampleRate(int32_t rate);
    int32_t getSampleRate();
    void pushAudio(const int16_t *stereo, size_t frames);
    size_t drainAudio(int16_t *out, size_t capacity);
    /** Why the game can't be heard (the sound stream wouldn't open, or stopped), empty while it can. */
    void setAudioProblem(const std::string &problem);
    std::string audioProblem();

    /**
     * Two screens stacked in one frame (a DS): this display draws only the upper one, and the
     * whole frame is kept, as RGBA, for the other display and the reader.
     */
    void setSplitScreens(bool split);
    bool isSplitScreens();
    void keepFrame(const void *data, unsigned width, unsigned height, size_t pitch, retro_pixel_format format);
    /** Rows [firstRow, firstRow + rows) of the last kept frame into [out]; false when it has none that big. */
    bool copyFrame(uint8_t *out, size_t capacity, unsigned firstRow, unsigned rows);
    std::pair<unsigned, unsigned> frameSize();
    /** Keeps the next frame the core draws, at its own size, even with one screen. */
    void requestFrame();
    /** Whether a frame was asked for; asking again clears it. */
    bool takeFrameRequest();

private:
    GenkanTap() = default;

    std::atomic<bool> frameRequested { false };
    std::mutex memoryLock;
    std::vector<Region> memory;
    void *(*systemRamData)(unsigned) = nullptr;
    size_t (*systemRamSize)(unsigned) = nullptr;

    std::mutex audioLock;
    std::vector<int16_t> ring;
    size_t ringStart = 0;
    size_t ringCount = 0;
    int32_t sampleRate = 0;

    std::atomic<bool> splitScreens { false };
    std::mutex frameLock;
    std::vector<uint8_t> frame;
    unsigned frameWidth = 0;
    unsigned frameHeight = 0;
    std::mutex problemLock;
    std::string problem;
};

}

#endif //LIBRETRODROID_GENKANTAP_H
