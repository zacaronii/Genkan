/* Genkan: see scripthost.h. */
#include "scripthost.h"

#include <algorithm>
#include <cstring>

#include "yomikata_reader.h"

namespace libretrodroid {

ScriptHost::~ScriptHost() {
    unload();
}

bool ScriptHost::fold(uint64_t address, uint64_t pointerMask, uint64_t ramBase, uint64_t *local) {
    uint64_t folded = address & pointerMask;
    if (folded < ramBase) return false;
    *local = folded - ramBase;
    return true;
}

std::string ScriptHost::load(const std::string &name, const std::string &source, const Game &forGame,
                             std::map<std::string, std::string> files, Memory read,
                             RegionSize size, RegionRead regionReader) {
    unload();
    game = forGame;
    memory = std::move(read);
    regionSize = std::move(size);
    regionRead = std::move(regionReader);
    data = std::move(files);

    yk_host host{};
    host.user = this;
    host.read = &ScriptHost::readHost;
    host.region_size = regionSize ? &ScriptHost::regionSizeHost : nullptr;
    host.region_read = regionRead ? &ScriptHost::regionReadHost : nullptr;
    host.data = &ScriptHost::dataHost;
    host.event = &ScriptHost::eventHost;

    yk_options options{};
    options.abi = YK_ABI;
    options.platform = game.platform.empty() ? nullptr : game.platform.c_str();
    options.game_id = game.gameId.c_str();
    options.build_id = game.buildId.empty() ? nullptr : game.buildId.c_str();

    reader = yk_open(&host, &options);
    if (reader == nullptr) return "the reader runtime couldn't start";
    if (yk_load(reader, name.c_str(), source.data(), source.size()) != 1) {
        std::string why = yk_error(reader);
        unload();
        return why.empty() ? "the script didn't load" : why;
    }
    // Its log lines from loading come out with the first frame's events.
    return "";
}

void ScriptHost::unload() {
    if (reader != nullptr) {
        yk_close(reader);
        reader = nullptr;
    }
    events.clear();
}

std::vector<ScriptHost::Event> ScriptHost::frame(uint64_t frame) {
    if (reader == nullptr) return {};
    yk_frame(reader, frame);
    std::vector<Event> out;
    out.swap(events);
    return out;
}

void ScriptHost::stateLoaded() {
    if (reader != nullptr) yk_state_loaded(reader);
}

void ScriptHost::picture(uint32_t width, uint32_t height) {
    if (reader != nullptr) yk_picture(reader, width, height);
}

std::string ScriptHost::error() const {
    return reader != nullptr ? std::string(yk_error(reader)) : std::string();
}

size_t ScriptHost::readHost(void *user, uint64_t address, void *out, size_t size) {
    auto *self = static_cast<ScriptHost *>(user);
    uint64_t local;
    if (!self->memory || !fold(address, self->game.pointerMask, self->game.ramBase, &local)) return 0;
    return self->memory(local, static_cast<uint8_t *>(out), size);
}

int64_t ScriptHost::regionSizeHost(void *user, const char *name) {
    auto *self = static_cast<ScriptHost *>(user);
    return self->regionSize ? self->regionSize(name) : -1;
}

size_t ScriptHost::regionReadHost(void *user, const char *name, uint64_t offset, void *out, size_t size) {
    auto *self = static_cast<ScriptHost *>(user);
    return self->regionRead ? self->regionRead(name, offset, static_cast<uint8_t *>(out), size) : 0;
}

int64_t ScriptHost::dataHost(void *user, const char *name, void *out, size_t capacity) {
    auto *self = static_cast<ScriptHost *>(user);
    auto found = self->data.find(name);
    if (found == self->data.end()) return -1;
    const std::string &file = found->second;
    if (capacity > 0) std::memcpy(out, file.data(), std::min(capacity, file.size()));
    return static_cast<int64_t>(file.size());
}

void ScriptHost::eventHost(void *user, int kind, const char *json, size_t length) {
    auto *self = static_cast<ScriptHost *>(user);
    self->events.emplace_back(kind, std::string(json, length));
}

}
