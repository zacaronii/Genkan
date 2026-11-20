/*
 *     Genkan's additions to LibretroDroid: what the reader needs from a running core.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

#include "genkantap.h"

#include <algorithm>
#include <cstring>

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
        memory.push_back(Region { d.start, d.len, host, mirror });
    }
}

void GenkanTap::addSystemRam(void *data, size_t size) {
    std::lock_guard<std::mutex> lock(memoryLock);
    // A memory map, when the core gave one, says more than the bare system RAM.
    if (!memory.empty() || data == nullptr || size == 0) return;
    memory.push_back(Region { 0, size, static_cast<uint8_t *>(data), false });
}

void GenkanTap::clear() {
    {
        std::lock_guard<std::mutex> lock(memoryLock);
        memory.clear();
    }
    std::lock_guard<std::mutex> lock(audioLock);
    ringStart = 0;
    ringCount = 0;
}

std::vector<GenkanTap::Region> GenkanTap::regions() {
    std::lock_guard<std::mutex> lock(memoryLock);
    return memory;
}

bool GenkanTap::read(uint64_t address, uint8_t *out, size_t size) {
    std::lock_guard<std::mutex> lock(memoryLock);
    for (const auto &r : memory) {
        if (address >= r.start && address + size <= r.start + r.length) {
            std::memcpy(out, r.host + (address - r.start), size);
            return true;
        }
    }
    return false;
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

}
