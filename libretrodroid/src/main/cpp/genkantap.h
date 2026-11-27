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
        // The descriptor's select and disconnect bits (memorymap.h): how the console's addresses
        // reach [host]; zero for plain memory from start for length bytes.
        uint64_t select = 0;
        uint64_t disconnect = 0;
        // Kept as words of this many bytes (2 or 4) in the host's order (memorymap.h): reads put each
        // word's bytes back in the console's order. 0: plain bytes.
        unsigned wordSize = 0;
    };

    /** A stretch of the core's system RAM the console sees at an address of its own. */
    struct Stretch {
        uint64_t start;
        uint64_t offset;
        uint64_t length;
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
    /**
     * A core whose system RAM is more than one of the console's stretches (mednafen_saturn's: the low
     * 1 MB of work RAM, then the high): each at the console's address. And the size of the words the
     * core keeps its memory in, in the host's order (2 or 4; 0 for plain bytes), for these and for the
     * blocks of a memory map it gives (mupen64plus_next's). Empty, the system RAM is one region from 0.
     * Set before the game loads, for the game.
     */
    void setSystemRamStretches(std::vector<Stretch> stretches, unsigned wordSize);
    /**
     * The size of the words the core keeps its video RAM in, in the host's order (2: Genesis Plus
     * GX's VDP); 0 for plain bytes. Reads give it in the console's order, as Yomikata's harness does.
     * Set before the game loads.
     */
    void setVideoRamWordSize(unsigned wordSize);
    /**
     * A cartridge's image as Yomikata's harness gives it (a Game Boy's file whole, a Famicom's past
     * its header), read at [CARTRIDGE_IMAGE_AT] on, where the console has nothing: a script checks its
     * build against it. Set before the game loads; empty, none.
     */
    void setCartridgeImage(std::vector<uint8_t> image);
    static constexpr uint64_t CARTRIDGE_IMAGE_AT = 0x01000000;
    void clear();
    std::vector<Region> regions();
    /**
     * [size] bytes at [address] (what snapshots and the text search read): by the core's map, or, when
     * reads are whole (setReadsWhole) and the map leaves the address out, from the block it lies in.
     */
    bool read(uint64_t address, uint8_t *out, size_t size);
    /**
     * The core's blocks read whole where its map leaves gaps in them (a PC Engine CD's card RAM), as
     * Yomikata's harness reads them for snapshots and searches. Scripts keep to the map. Set before the game loads.
     */
    void setReadsWhole(bool whole);
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
    /** A lifted finger leaves the core's pointer where it was (a Wii's): see PointerState::touch. */
    void setKeepPointer(bool keep);
    bool isKeepPointer();
    /** The game the core named ("SLPM-12345 1a2b3c4d", see gameid.h); empty until it does. */
    void setGameId(const std::string &id);
    std::string gameId();
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
    std::vector<Stretch> stretches;
    // The words the core keeps its memory in, map and system RAM alike (setSystemRamStretches).
    unsigned wordSize = 0;
    // The video RAM's word size (setVideoRamWordSize).
    unsigned videoRamWordSize = 0;
    // The cartridge's image (setCartridgeImage).
    std::vector<uint8_t> cartridge;
    // The core's own regions (its map, or its system RAM), under memoryLock.
    std::vector<Region> coreRegions();

    std::mutex audioLock;
    std::vector<int16_t> ring;
    size_t ringStart = 0;
    size_t ringCount = 0;
    int32_t sampleRate = 0;

    std::atomic<bool> splitScreens { false };
    std::atomic<bool> keepPointer { false };
    std::atomic<bool> readsWhole { false };
    std::mutex gameIdLock;
    std::string gameIdValue;
    std::mutex frameLock;
    std::vector<uint8_t> frame;
    unsigned frameWidth = 0;
    unsigned frameHeight = 0;
    std::mutex problemLock;
    std::string problem;
};

}

#endif //LIBRETRODROID_GENKANTAP_H
