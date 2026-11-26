// Genkan: reading a console's address through a core's memory descriptor (memorymap.h), on made-up maps
// shaped as bsnes gives them: a LoROM cartridge of 1.5 MB, a HiROM one of 3 MB, plain work RAM.
#include <cstdio>
#include <optional>

#include "memorymap.h"

using libretrodroid::MemoryDescriptor;
using libretrodroid::locateAddress;
using libretrodroid::readableFrom;
using libretrodroid::reduceAddress;

namespace {

int failures = 0;

void check(bool ok, const char *what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        failures++;
    }
}

bool at(const MemoryDescriptor &d, uint64_t address, uint64_t offset) {
    auto found = locateAddress(d, address);
    return found && *found == offset;
}

}

int main() {
    check(reduceAddress(0x018000, 0x8000) == 0x8000, "a disconnected bit is taken out, the bits above move down");
    check(reduceAddress(0x7FFF, 0x8000) == 0x7FFF, "bits under a disconnected one stay");

    // LoROM: banks 00-7F and 80-FF at 8000-FFFF, bit 15 disconnected.
    const MemoryDescriptor low { 0x008000, 0x180000, 0x808000, 0x8000 };
    const MemoryDescriptor lowMirror { 0x808000, 0x180000, 0x808000, 0x8000 };
    check(at(low, 0x008000, 0), "bank 0's upper half is the cartridge's start");
    check(at(low, 0x00FFC0, 0x7FC0), "the header in bank 0");
    check(at(low, 0x018000, 0x8000), "bank 1 follows bank 0's half");
    check(at(low, 0x058000, 0x28000), "bank 5 at 5 x 0x8000");
    check(at(low, 0x2F8000, 0x178000), "the last bank");
    check(at(lowMirror, 0x858000, 0x28000), "the 0x80+ banks are the same cartridge");
    check(at(low, 0x308000, 0x80000), "past 1.5 MB wraps by its highest bit, not by modulo");
    check(!locateAddress(low, 0x000000), "a bank's lower half isn't the cartridge's");
    check(!locateAddress(low, 0x858000), "nor the other half of the console's addresses");
    check(readableFrom(low, 0x01FFF0, 0xFFF0) == 0x10, "a read stops at a bank's end");
    check(readableFrom(low, 0x018000, 0x8000) == 0x8000, "a whole bank reads");

    // HiROM: banks C0-FF whole, 3 MB.
    const MemoryDescriptor high { 0xC00000, 0x300000, 0xC00000, 0 };
    check(at(high, 0xC0B340, 0xB340), "HiROM reads straight");
    check(at(high, 0xF00000, 0x100000), "past 3 MB wraps by its highest bit");
    check(readableFrom(high, 0xC0FFF0, 0xFFF0) == 0x300000 - 0xFFF0, "no bank ends without disconnected bits");

    // Plain memory, no select: from start for its length only.
    const MemoryDescriptor ram { 0x7E0000, 0x20000, 0, 0 };
    check(at(ram, 0x7E0000, 0) && at(ram, 0x7FFFFF, 0x1FFFF), "plain memory reads from its start");
    check(!locateAddress(ram, 0x800000) && !locateAddress(ram, 0x7DFFFF), "and nowhere else");
    check(readableFrom(ram, 0x7FFFF0, 0x1FFF0) == 0x10, "to its end");

    if (failures == 0) std::printf("memory map: passed\n");
    return failures == 0 ? 0 : 1;
}
