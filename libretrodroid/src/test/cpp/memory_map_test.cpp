// Genkan: reading a console's address through a core's memory descriptor (memorymap.h), on made-up maps
// shaped as bsnes gives them: a LoROM cartridge of 1.5 MB, a HiROM one of 3 MB, plain work RAM.
#include <cstdio>
#include <cstring>
#include <optional>

#include "memorymap.h"

using libretrodroid::MemoryDescriptor;
using libretrodroid::locateAddress;
using libretrodroid::readableFrom;
using libretrodroid::reduceAddress;
using libretrodroid::unswapWords;
using libretrodroid::wholeOffset;
using libretrodroid::wordSpanLength;
using libretrodroid::wordSpanStart;

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

    // A PC Engine CD's card RAM, 192 KB under select 0xFFFD0000: the map takes its first and third
    // 64 KB at their places and not the middle, which a whole read takes.
    const MemoryDescriptor card { 0x0D0000, 0x30000, 0xFFFD0000, 0 };
    check(at(card, 0x0D0000, 0) && at(card, 0x0F0000, 0x20000), "the card's first and third 64 KB by the map");
    check(!locateAddress(card, 0x0E0000), "not its middle");
    check(wholeOffset(card, 0x0E0000, 0x10000) == 0x10000, "read whole, the middle is the block's second 64 KB");
    check(wholeOffset(card, 0x0D0000, 0x30000) == 0, "all of it at once");
    check(!wholeOffset(card, 0x0FFFFF, 2), "not past its end");
    check(!wholeOffset(card, 0x0CFFFF, 1) && !wholeOffset(card, 0x100000, 1), "nor outside it");

    // Work RAM kept as 16-bit words, each byte pair swapped (mednafen_saturn's): the console's
    // "SEGA" (53 45 47 41) lies as 45 53 41 47.
    const uint8_t words[] = { 0x45, 0x53, 0x41, 0x47, 0x00, 0x21 };
    uint8_t out[6] = {};
    check(wordSpanStart(0, 2) == 0 && wordSpanLength(0, 4, 2) == 4, "an aligned read fetches its own words");
    unswapWords(words, 0, out, 4, 2);
    check(out[0] == 'S' && out[1] == 'E' && out[2] == 'G' && out[3] == 'A', "each pair goes back in the console's order");
    check(wordSpanStart(1, 2) == 0 && wordSpanLength(1, 2, 2) == 4, "an odd start fetches the word it starts in and the next");
    unswapWords(words, 1, out, 2, 2);
    check(out[0] == 'E' && out[1] == 'G', "an odd start reads from the pair's second byte");
    check(wordSpanLength(5, 1, 2) == 2, "one byte fetches its word");
    unswapWords(words + 4, 5, out, 1, 2);
    check(out[0] == 0x00, "the word's second byte, as the console has it");
    unswapWords(words + 4, 4, out, 1, 2);
    check(out[0] == 0x21, "and its first");

    // RDRAM kept as 32-bit words, each one's four bytes reversed (mupen64plus_next's): the console's
    // "NUS-" (4E 55 53 2D) then "ABCD" lie as 2D 53 55 4E 44 43 42 41.
    const uint8_t rdram[] = { 0x2D, 0x53, 0x55, 0x4E, 0x44, 0x43, 0x42, 0x41 };
    uint8_t text[8] = {};
    check(wordSpanStart(0, 4) == 0 && wordSpanLength(0, 8, 4) == 8, "an aligned read fetches its own 32-bit words");
    unswapWords(rdram, 0, text, 8, 4);
    check(std::memcmp(text, "NUS-ABCD", 8) == 0, "each word's four bytes go back in the console's order");
    check(wordSpanStart(3, 4) == 0 && wordSpanLength(3, 2, 4) == 8, "a read across two words fetches both");
    unswapWords(rdram, 3, text, 2, 4);
    check(text[0] == '-' && text[1] == 'A', "from the first word's last byte into the next word's first");
    check(wordSpanStart(6, 4) == 4 && wordSpanLength(6, 1, 4) == 4, "one byte fetches its word");
    unswapWords(rdram + 4, 6, text, 1, 4);
    check(text[0] == 'C', "the word's third byte, as the console has it");

    if (failures == 0) std::printf("memory map: passed\n");
    return failures == 0 ? 0 : 1;
}
