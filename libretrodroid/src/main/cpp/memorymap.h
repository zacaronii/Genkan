#ifndef LIBRETRODROID_MEMORYMAP_H
#define LIBRETRODROID_MEMORYMAP_H

#include <algorithm>
#include <cstdint>
#include <optional>

namespace libretrodroid {

/**
 * Genkan: where one of the console's addresses is in a core's memory descriptor, by libretro's rule
 * as RetroArch and Yomikata's harness (tools/src/yomikata/replay/libretro.py) read it, so a script
 * reads the same on both. A descriptor takes an address that agrees with its start in the bits of
 * its select (with no select: from start for len bytes); the bits of disconnect are then taken out
 * (a LoROM cartridge's bit 15: each bank's upper half follows the last's), and what is past len
 * wraps by dropping its highest bit, again until it fits (a size that isn't a power of two:
 * mirrors), not by modulo.
 */
struct MemoryDescriptor {
    uint64_t start;
    uint64_t length;
    uint64_t select;
    uint64_t disconnect;
};

/** [address] with the bits of [mask] taken out and those above them moved down. */
inline uint64_t reduceAddress(uint64_t address, uint64_t mask) {
    while (mask != 0) {
        uint64_t low = (mask - 1) & ~mask;
        address = (address & low) | ((address >> 1) & ~low);
        mask = (mask & (mask - 1)) >> 1;
    }
    return address;
}

/** The offset into [d]'s memory for [address], or nothing when the address isn't [d]'s. */
inline std::optional<uint64_t> locateAddress(const MemoryDescriptor &d, uint64_t address) {
    if (d.select != 0) {
        if (((d.start ^ address) & d.select) != 0) return std::nullopt;
    } else if (address < d.start || address - d.start >= d.length) {
        return std::nullopt;
    }
    uint64_t at = reduceAddress(address - d.start, d.disconnect);
    while (at >= d.length && at != 0) {
        uint64_t highest = uint64_t { 1 } << (63 - __builtin_clzll(at));
        at -= highest;
    }
    if (at >= d.length) return std::nullopt;
    return at;
}

/**
 * How many bytes from [address] (at offset [at]) read on in [d]'s memory as the console sees
 * them: to the end of its memory, and with disconnected bits no further than the next of them
 * (a LoROM bank's end: what follows in the console's addresses isn't what follows in the cartridge).
 */
inline uint64_t readableFrom(const MemoryDescriptor &d, uint64_t address, uint64_t at) {
    uint64_t most = d.length - at;
    if (d.disconnect != 0) {
        uint64_t lowest = d.disconnect & (~d.disconnect + 1);
        most = std::min(most, lowest - ((address - d.start) & (lowest - 1)));
    }
    return most;
}

}

#endif //LIBRETRODROID_MEMORYMAP_H
