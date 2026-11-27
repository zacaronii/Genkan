/*
 *     Genkan's additions to LibretroDroid: what the reader needs from a running core.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

#include "genkantap.h"
#include "memorymap.h"

#include <algorithm>
#include <cstring>
#include <sys/uio.h>
#include <vector>
#include <unistd.h>

namespace libretrodroid {

void GenkanTap::setMemoryMaps(const struct retro_memory_map *map) {
    std::lock_guard<std::mutex> lock(memoryLock);
    memory.clear();
    for (unsigned i = 0; i < map->num_descriptors; i++) {
        const auto &d = map->descriptors[i];
        if (d.ptr == nullptr || d.len == 0) continue;
        auto *host = static_cast<uint8_t *>(d.ptr) + d.offset;
        bool mirror = std::any_of(memory.begin(), memory.end(), [&](const Region &r) {
            return host >= r.host && host < r.host + r.length;
        });
        // A core that keeps its memory in words of the host's order keeps its map's blocks so too
        // (mupen64plus_next's RDRAM, RSP memory and cartridge): read back in the console's order.
        memory.push_back(Region { d.start, d.len, host, mirror, d.select, d.disconnect, wordSize });
    }
}

void GenkanTap::setSystemRam(void *(*data)(unsigned), size_t (*size)(unsigned)) {
    std::lock_guard<std::mutex> lock(memoryLock);
    systemRamData = data;
    systemRamSize = size;
}

void GenkanTap::setSystemRamStretches(std::vector<Stretch> given, unsigned wordSize) {
    std::lock_guard<std::mutex> lock(memoryLock);
    stretches = std::move(given);
    this->wordSize = wordSize;
}

void GenkanTap::clear() {
    {
        std::lock_guard<std::mutex> lock(frameLock);
        frame.clear();
        frameWidth = 0;
        frameHeight = 0;
    }
    {
        std::lock_guard<std::mutex> lock(memoryLock);
        memory.clear();
        cartridge.clear();
        systemRamData = nullptr;
        systemRamSize = nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(gameIdLock);
        gameIdValue.clear();
    }
    std::lock_guard<std::mutex> lock(audioLock);
    ringStart = 0;
    ringCount = 0;
}

bool GenkanTap::hasCartridgeImage() {
    std::lock_guard<std::mutex> lock(memoryLock);
    return !cartridge.empty();
}

void GenkanTap::setCartridgeImage(std::vector<uint8_t> image) {
    std::lock_guard<std::mutex> lock(memoryLock);
    cartridge = std::move(image);
}

std::vector<GenkanTap::Region> GenkanTap::regions() {
    std::lock_guard<std::mutex> lock(memoryLock);
    std::vector<Region> out = coreRegions();
    // The cartridge's image, whole, at an address of its own past what the console has (Yomikata's).
    if (!cartridge.empty()) out.push_back(Region { CARTRIDGE_IMAGE_AT, cartridge.size(), cartridge.data(), false });
    return out;
}

std::vector<GenkanTap::Region> GenkanTap::coreRegions() {
    // A memory map, when the core gave one, says more than the bare system RAM.
    if (!memory.empty() || systemRamData == nullptr) return memory;
    auto *data = static_cast<uint8_t *>(systemRamData(RETRO_MEMORY_SYSTEM_RAM));
    size_t size = systemRamSize(RETRO_MEMORY_SYSTEM_RAM);
    if (data == nullptr || size == 0) return {};
    if (stretches.empty()) return { Region { 0, size, data, false } };
    std::vector<Region> out;
    for (const auto &s : stretches) {
        if (s.offset >= size) continue;
        out.push_back(Region { s.start, std::min<uint64_t>(s.length, size - s.offset), data + s.offset, false, 0, 0, wordSize });
    }
    return out;
}

namespace {

MemoryDescriptor descriptorOf(const GenkanTap::Region &r) {
    return MemoryDescriptor { r.start, r.length, r.select, r.disconnect };
}

/**
 * [size] bytes at offset [at] of [r] into [out], in the console's order; how many came. Copied by the
 * kernel, so memory the core unmapped or never mapped fails the read instead of crashing the game.
 */
size_t copyOut(const GenkanTap::Region &r, uint64_t at, uint8_t *out, size_t size) {
    if (r.wordSize < 2) {
        struct iovec local { out, size };
        struct iovec remote { r.host + at, size };
        ssize_t got = process_vm_readv(getpid(), &local, 1, &remote, 1, 0);
        return got > 0 ? static_cast<size_t>(got) : 0;
    }
    // Whole words, then each one's bytes back in the console's order; a word-kept region's length is
    // a whole number of words.
    std::vector<uint8_t> span(wordSpanLength(at, size, r.wordSize));
    struct iovec local { span.data(), span.size() };
    struct iovec remote { r.host + wordSpanStart(at, r.wordSize), span.size() };
    if (process_vm_readv(getpid(), &local, 1, &remote, 1, 0) != static_cast<ssize_t>(span.size())) return 0;
    unswapWords(span.data(), at, out, size, r.wordSize);
    return size;
}

}

bool GenkanTap::read(uint64_t address, uint8_t *out, size_t size) {
    for (const auto &r : regions()) {
        auto d = descriptorOf(r);
        auto at = locateAddress(d, address);
        if (!at) continue;
        // The first region that takes the address answers: the whole read there, or none.
        if (size > readableFrom(d, address, *at)) return false;
        return copyOut(r, *at, out, size) == size;
    }
    if (!readsWhole) return false;
    // Where the map leaves a gap in a block of the core's own, the block read whole (setReadsWhole).
    for (const auto &r : regions()) {
        auto at = wholeOffset(descriptorOf(r), address, size);
        if (at) return copyOut(r, *at, out, size) == size;
    }
    return false;
}

size_t GenkanTap::readClipped(const std::vector<Region> &among, uint64_t address, uint8_t *out, size_t size) {
    for (const auto &r : among) {
        auto d = descriptorOf(r);
        auto at = locateAddress(d, address);
        if (!at) continue;
        size_t n = static_cast<size_t>(std::min<uint64_t>(size, readableFrom(d, address, *at)));
        return copyOut(r, *at, out, n);
    }
    return 0;
}

size_t GenkanTap::videoRamSize() {
    std::lock_guard<std::mutex> lock(memoryLock);
    if (systemRamData == nullptr || systemRamData(RETRO_MEMORY_VIDEO_RAM) == nullptr) return 0;
    return systemRamSize(RETRO_MEMORY_VIDEO_RAM);
}

bool GenkanTap::readVideoRam(uint64_t offset, uint8_t *out, size_t size) {
    uint8_t *data;
    size_t total;
    {
        std::lock_guard<std::mutex> lock(memoryLock);
        if (systemRamData == nullptr) return false;
        data = static_cast<uint8_t *>(systemRamData(RETRO_MEMORY_VIDEO_RAM));
        total = systemRamSize(RETRO_MEMORY_VIDEO_RAM);
    }
    if (data == nullptr || offset + size > total) return false;
    struct iovec local { out, size };
    struct iovec remote { data + offset, size };
    return process_vm_readv(getpid(), &local, 1, &remote, 1, 0) == static_cast<ssize_t>(size);
}

void GenkanTap::setSampleRate(int32_t rate) {
    std::lock_guard<std::mutex> lock(audioLock);
    sampleRate = rate;
    // Four seconds: the reader drains it several times a second.
    ring.assign(std::max(rate, 1) * 4, 0);
    ringStart = 0;
    ringCount = 0;
}

int32_t GenkanTap::getSampleRate() {
    std::lock_guard<std::mutex> lock(audioLock);
    return sampleRate;
}

void GenkanTap::pushAudio(const int16_t *stereo, size_t frames) {
    std::lock_guard<std::mutex> lock(audioLock);
    if (ring.empty()) return;
    for (size_t i = 0; i < frames; i++) {
        auto mono = static_cast<int16_t>((stereo[i * 2] + stereo[i * 2 + 1]) / 2);
        ring[(ringStart + ringCount) % ring.size()] = mono;
        if (ringCount < ring.size()) {
            ringCount++;
        } else {
            // Nobody drained it in time: the oldest sample goes.
            ringStart = (ringStart + 1) % ring.size();
        }
    }
}

size_t GenkanTap::drainAudio(int16_t *out, size_t capacity) {
    std::lock_guard<std::mutex> lock(audioLock);
    size_t n = std::min(capacity, ringCount);
    for (size_t i = 0; i < n; i++) out[i] = ring[(ringStart + i) % ring.size()];
    ringStart = (ringStart + n) % std::max<size_t>(ring.size(), 1);
    ringCount -= n;
    return n;
}

void GenkanTap::setSplitScreens(bool split) {
    splitScreens = split;
}

bool GenkanTap::isSplitScreens() {
    return splitScreens;
}

void GenkanTap::setReadsWhole(bool whole) {
    readsWhole = whole;
}

void GenkanTap::setKeepPointer(bool keep) {
    keepPointer = keep;
}

bool GenkanTap::isKeepPointer() {
    return keepPointer;
}

void GenkanTap::setGameId(const std::string &id) {
    std::lock_guard<std::mutex> lock(gameIdLock);
    gameIdValue = id;
}

std::string GenkanTap::gameId() {
    std::lock_guard<std::mutex> lock(gameIdLock);
    return gameIdValue;
}

void GenkanTap::keepFrame(const void *data, unsigned width, unsigned height, size_t pitch, retro_pixel_format format) {
    std::lock_guard<std::mutex> lock(frameLock);
    frame.resize(static_cast<size_t>(width) * height * 4);
    frameWidth = width;
    frameHeight = height;
    auto *out = frame.data();
    for (unsigned y = 0; y < height; y++) {
        const auto *row = static_cast<const uint8_t *>(data) + y * pitch;
        for (unsigned x = 0; x < width; x++, out += 4) {
            uint8_t r, g, b;
            if (format == RETRO_PIXEL_FORMAT_XRGB8888) {
                uint32_t p = reinterpret_cast<const uint32_t *>(row)[x];
                r = p >> 16; g = p >> 8; b = p;
            } else if (format == RETRO_PIXEL_FORMAT_RGB565) {
                uint16_t p = reinterpret_cast<const uint16_t *>(row)[x];
                r = (p >> 11 & 0x1F) << 3; g = (p >> 5 & 0x3F) << 2; b = (p & 0x1F) << 3;
            } else {
                uint16_t p = reinterpret_cast<const uint16_t *>(row)[x];
                r = (p >> 10 & 0x1F) << 3; g = (p >> 5 & 0x1F) << 3; b = (p & 0x1F) << 3;
            }
            out[0] = r; out[1] = g; out[2] = b; out[3] = 0xFF;
        }
    }
}

bool GenkanTap::copyFrame(uint8_t *out, size_t capacity, unsigned firstRow, unsigned rows) {
    std::lock_guard<std::mutex> lock(frameLock);
    size_t row = static_cast<size_t>(frameWidth) * 4;
    if (rows == 0 || firstRow + rows > frameHeight || row * rows > capacity) return false;
    memcpy(out, frame.data() + row * firstRow, row * rows);
    return true;
}

void GenkanTap::requestFrame() {
    {
        std::lock_guard<std::mutex> lock(frameLock);
        if (!splitScreens) {
            frameWidth = 0;
            frameHeight = 0;
        }
    }
    frameRequested = true;
}

bool GenkanTap::takeFrameRequest() {
    return frameRequested.exchange(false);
}

std::pair<unsigned, unsigned> GenkanTap::frameSize() {
    std::lock_guard<std::mutex> lock(frameLock);
    return { frameWidth, frameHeight };
}


void GenkanTap::setAudioProblem(const std::string &value) {
    std::lock_guard<std::mutex> lock(problemLock);
    problem = value;
}

std::string GenkanTap::audioProblem() {
    std::lock_guard<std::mutex> lock(problemLock);
    return problem;
}

}
