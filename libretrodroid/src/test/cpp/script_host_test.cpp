// Genkan: the Yomikata script host on made-up memory. A PS1's 2 MB of RAM, which its core maps from 0
// while games use 0x80000000 and its mirrors; and a GameCube's MEM1 at 0x80000000, big-endian, with a
// made-up disc header. Scripts here are made up too: no game's addresses or text.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "scripthost.h"

using libretrodroid::ScriptHost;

namespace {

int failures = 0;

void check(bool ok, const char *what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        failures++;
    }
}

/** Memory of `size` bytes at the core's address `start`, read the way GenkanTap clips a read. */
ScriptHost::Memory region(std::vector<uint8_t> &bytes, uint64_t start) {
    return [&bytes, start](uint64_t address, uint8_t *out, size_t size) -> size_t {
        if (address < start || address >= start + bytes.size()) return 0;
        size_t n = std::min<size_t>(size, start + bytes.size() - address);
        std::memcpy(out, bytes.data() + (address - start), n);
        return n;
    };
}

bool has(const std::vector<ScriptHost::Event> &events, size_t i, int kind, const char *part) {
    return i < events.size() && events[i].first == kind && events[i].second.find(part) != std::string::npos;
}

void ps1() {
    std::vector<uint8_t> ram(2 * 1024 * 1024);
    const uint8_t word[] = {0x78, 0x56, 0x34, 0x12};
    std::memcpy(ram.data() + 0x1000, word, 4);
    std::memcpy(ram.data() + ram.size() - 2, "AB", 2);
    ScriptHost host;
    ScriptHost::Game game{"ps1", "SXXX-00000", "", 0x1FFFFFFF, 0};
    // The game's three views of its RAM read the same; a read off the end is clipped, one past it empty.
    const std::string source = R"lua(
local ok = mem.u32(0x80001000) == 0x12345678 and mem.u32(0xA0001000) == 0x12345678
    and mem.u32(0x00001000) == 0x12345678 and mem.u32be(0x80001000) == 0x78563412
    and mem.read(0x801FFFFE, 8) == "AB" and mem.read(0x80200000, 4) == "" and mem.u32(0x801FFFFE) == nil
    and reader.data("tbl") == "1=a" and reader.data("none") == nil
reader.on_frame(function(frame)
    if frame ~= 7 then return end
    reader.emit{ text = ok and "pass" or "fail", id = "first" }
    reader.emit{ text = "menu", channel = "interface", window = "w" }
    reader.log("done")
end)
)lua";
    std::string error = host.load("ps1.lua", source, game, {{"tbl", "1=a"}}, region(ram, 0));
    check(error.empty(), ("the PS1 script loads: " + error).c_str());
    // The runtime's line about the load comes out with the first frame, before anything the script says.
    auto early = host.frame(6);
    check(has(early, 0, 4, "loaded") && early.size() == 1, "before the frame the script waits for, only the load's log line");
    auto events = host.frame(7);
    // In the order the script gave them, its own channel passed through for the reader to sort.
    check(has(events, 0, 1, R"("text":"pass")"), "PS1 reads: mirrors, both byte orders, clipped reads, data files");
    check(has(events, 1, 1, R"("channel":"interface")"), "the interface text comes next, unfiltered here");
    check(has(events, 2, 4, "done"), "then the log line");
    check(events.size() == 3, "nothing else");
}

void gamecube() {
    std::vector<uint8_t> mem1(0x1800000);
    std::memcpy(mem1.data(), "GTST01", 6);
    const uint8_t magic[] = {0xC2, 0x33, 0x9F, 0x3D};
    std::memcpy(mem1.data() + 0x1C, magic, 4);
    const uint8_t pointer[] = {0x80, 0x00, 0x20, 0x00};
    std::memcpy(mem1.data() + 0x100, pointer, 4);
    std::memcpy(mem1.data() + 0x2000, "\x82\xa0\x82\xa2", 5);
    ScriptHost::Game game{"gc", "GTST01", "GTST01-00", 0x81FFFFFF, 0};
    const std::string script = R"lua(
if mem.read(0x80000000, 6) ~= "GTST01" or mem.u32be(0x8000001C) ~= 0xC2339F3D then
    error("this script is for another build of the game", 0)
end
reader.on_state_loaded(function() reader.forget() end)
reader.on_frame(function()
    -- A big-endian pointer, and Shift-JIS where it points; the uncached mirror reads the same.
    local at = mem.u32be(0x80000100)
    local line = text.sjis(mem.cstr(at)) .. text.sjis(mem.cstr(at | 0x40000000))
    if reader.changed("line", line) then reader.emit{ text = line, id = text.hash(line) } end
end)
)lua";
    ScriptHost host;
    std::string error = host.load("gc.lua", script, game, {}, region(mem1, 0x80000000));
    check(error.empty(), ("the GameCube script loads: " + error).c_str());
    auto events = host.frame(1);
    check(has(events, 0, 4, "loaded") && has(events, 1, 1, "あいあい"), "MEM1 read big-endian through its pointer, Shift-JIS decoded");
    check(host.frame(2).empty(), "the same text isn't emitted again");
    host.stateLoaded();
    check(has(host.frame(3), 0, 1, "あいあい"), "after a state load the script says it again");

    // Another disc: the script's own check refuses it, and the host has no script then.
    std::memcpy(mem1.data(), "GOTH02", 6);
    error = host.load("gc.lua", script, game, {}, region(mem1, 0x80000000));
    check(error.find("another build") != std::string::npos, ("another disc is refused: " + error).c_str());
    check(!host.loaded() && host.frame(4).empty(), "nothing runs after a refusal");
}

void refusals() {
    std::vector<uint8_t> ram(16);
    ScriptHost host;
    ScriptHost::Game game{"gc", "GTST01", "", 0xFFFFFFFF, 0};
    std::string error = host.load("later.lua", "-- reader: runtime 99\nreader.on_frame(function() end)\n", game, {}, region(ram, 0));
    check(error.find("later reader runtime") != std::string::npos, ("a later runtime's script is refused: " + error).c_str());
    error = host.load("vram.lua", "-- reader: needs vram\n", game, {}, region(ram, 0));
    check(error.find("vram") != std::string::npos, ("a region the host hasn't got is refused: " + error).c_str());
    error = host.load("bad.lua", "this is not lua", game, {}, region(ram, 0));
    check(!error.empty() && !host.loaded(), "a syntax error is refused");
    // Below the base a core maps from, nothing is read.
    uint64_t local = 0;
    check(!ScriptHost::fold(0x00001000, 0xFFFFFFFF, 0x08000000, &local), "an address below the base folds to nothing");
    check(ScriptHost::fold(0x48001000, 0x3FFFFFFF, 0x08000000, &local) && local == 0x1000, "a PSP mirror folds onto its RAM");
}

}

int main() {
    ps1();
    gamecube();
    refusals();
    if (failures > 0) {
        std::fprintf(stderr, "%d failed\n", failures);
        return EXIT_FAILURE;
    }
    std::printf("script host: passed\n");
    return EXIT_SUCCESS;
}
