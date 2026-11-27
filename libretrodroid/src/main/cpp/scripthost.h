/*
 * Genkan: runs a Yomikata script (third_party/yomikata/runtime, docs/script-api.md there) against the
 * running game's memory, between two frames, and keeps what it says until they're handed over.
 *
 * Nothing here knows libretro or Android: the memory comes through [Memory], so the host is built and
 * tested on its own (src/test/cpp).
 */
#ifndef LIBRETRODROID_SCRIPTHOST_H
#define LIBRETRODROID_SCRIPTHOST_H

#include <cstdint>
#include <functional>
#include <map>
#include <string>
#include <utility>
#include <vector>

struct yk_reader;

namespace libretrodroid {

class ScriptHost {
public:
    /**
     * The core's memory by the core's own addresses: copies up to `size` bytes at `address` into `out`
     * and returns how many it copied, fewer where a region ends, 0 where there is none.
     */
    using Memory = std::function<size_t(uint64_t address, uint8_t *out, size_t size)>;
    /** A named region ("vram"): its size, or -1 without one; and a read of it from an offset. */
    using RegionSize = std::function<int64_t(const std::string &name)>;
    using RegionRead = std::function<size_t(const std::string &name, uint64_t offset, uint8_t *out, size_t size)>;

    /** What the game is, for the script's options and the address folding. */
    struct Game {
        std::string platform; // the catalog's name: "gc", "ps1" …
        std::string gameId;
        std::string buildId;  // empty when unknown
        uint64_t pointerMask = 0xFFFFFFFFull; // folds a mirror onto the address the core maps
        uint64_t ramBase = 0;                 // subtracted after folding, for a core whose RAM starts at 0
        bool cartridgeImage = false;          // the cartridge's image is served at CARTRIDGE_IMAGE_AT on
    };

    /** Where the cartridge's image is (GenkanTap::CARTRIDGE_IMAGE_AT), past what any console has. */
    static constexpr uint64_t CARTRIDGE_IMAGE_AT = 0x01000000;

    /** One event as the runtime gives it: YK_TEXT, YK_LAYOUT, YK_CURSOR or YK_LOG, and its JSON. */
    using Event = std::pair<int, std::string>;

    ScriptHost() = default;
    ~ScriptHost();
    ScriptHost(const ScriptHost &) = delete;
    ScriptHost &operator=(const ScriptHost &) = delete;

    /**
     * Loads `source` (named `name`, for messages) for `game`, with its data files by name, in place of
     * the script before. Returns "" when it loaded, else why not: the runtime's error.
     */
    std::string load(const std::string &name, const std::string &source, const Game &game,
                     std::map<std::string, std::string> data, Memory memory,
                     RegionSize regionSize = nullptr, RegionRead regionRead = nullptr);
    void unload();
    bool loaded() const { return reader != nullptr; }

    /** Runs the script's frame functions for frame `frame`; the events they gave, in order. */
    std::vector<Event> frame(uint64_t frame);
    /** After a savestate was loaded, before the next frame. */
    void stateLoaded();
    void picture(uint32_t width, uint32_t height);
    /** The runtime's last error, or "". */
    std::string error() const;

    /**
     * The core's address for the game's `address`: folded through the mirrors, then less the base. The
     * cartridge's image, when [cartridgeImage], is Genkan's own and past the console's addresses: an
     * address from [CARTRIDGE_IMAGE_AT] on is read as it is, never masked into the console's.
     */
    static bool fold(uint64_t address, uint64_t pointerMask, uint64_t ramBase, uint64_t *local, bool cartridgeImage = false);

private:
    static size_t readHost(void *user, uint64_t address, void *out, size_t size);
    static int64_t regionSizeHost(void *user, const char *name);
    static size_t regionReadHost(void *user, const char *name, uint64_t offset, void *out, size_t size);
    static int64_t dataHost(void *user, const char *name, void *out, size_t capacity);
    static void eventHost(void *user, int kind, const char *json, size_t length);

    yk_reader *reader = nullptr;
    Game game;
    Memory memory;
    RegionSize regionSize;
    RegionRead regionRead;
    std::map<std::string, std::string> data;
    std::vector<Event> events;
};

}

#endif
