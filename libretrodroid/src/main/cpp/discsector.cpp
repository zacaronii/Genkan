/*
 *     Genkan's additions to LibretroDroid: one sector of a disc image's data track.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

#include "discsector.h"

#include <cstdio>
#include <cstring>
#include <memory>
#include <string>

#include <libchdr/cdrom.h>
#include <libchdr/chd.h>

namespace libretrodroid {

namespace {

constexpr uint32_t USER_DATA = 2048;

struct ChdCloser {
    void operator()(chd_file *chd) const { chd_close(chd); }
};

/** Where a frame's 2048 bytes of user data start, by the track's type; -1 for audio or a type not read. */
int userDataOffset(const std::string &type) {
    if (type == "MODE1_RAW") return 16;                 // sync and header first
    if (type == "MODE2_RAW") return 24;                 // and the subheader (form 1)
    if (type == "MODE1" || type == "MODE2_FORM1") return 0;
    return -1;
}

}

std::vector<uint8_t> chdDataSector(const char *path, uint32_t sector) {
    chd_file *opened = nullptr;
    if (chd_open(path, CHD_OPEN_READ, nullptr, &opened) != CHDERR_NONE) return {};
    std::unique_ptr<chd_file, ChdCloser> chd(opened);
    const chd_header *header = chd_get_header(chd.get());
    if (header->unitbytes != CD_FRAME_SIZE || header->hunkbytes % header->unitbytes != 0) return {};

    // The tracks in order, each padded to a multiple of 4 frames in the image (CHT2, or CHTR in
    // older ones): the first that isn't audio is the data track.
    uint32_t frame = 0;
    for (uint32_t index = 0;; index++) {
        char text[256] = {};
        uint32_t length = 0;
        int track = 0, frames = 0, pregap = 0, postgap = 0;
        char type[64] = {}, subtype[64] = {}, pgtype[64] = {}, pgsub[64] = {};
        if (chd_get_metadata(chd.get(), CDROM_TRACK_METADATA2_TAG, index, text, sizeof(text) - 1, &length, nullptr, nullptr) == CHDERR_NONE) {
            if (std::sscanf(text, CDROM_TRACK_METADATA2_FORMAT, &track, type, subtype, &frames, &pregap, pgtype, pgsub, &postgap) != 8) return {};
        } else if (chd_get_metadata(chd.get(), CDROM_TRACK_METADATA_TAG, index, text, sizeof(text) - 1, &length, nullptr, nullptr) == CHDERR_NONE) {
            if (std::sscanf(text, CDROM_TRACK_METADATA_FORMAT, &track, type, subtype, &frames) != 4) return {};
        } else {
            return {};
        }
        int offset = userDataOffset(type);
        if (offset >= 0) {
            // A pregap the image keeps (PGTYPE V...) comes before the track's own first sector.
            uint32_t start = frame + (pgtype[0] == 'V' ? (uint32_t) pregap : 0);
            if (sector >= (uint32_t) frames - (start - frame)) return {};
            uint32_t at = start + sector;
            uint32_t perHunk = header->hunkbytes / header->unitbytes;
            std::vector<uint8_t> hunk(header->hunkbytes);
            if (chd_read(chd.get(), at / perHunk, hunk.data()) != CHDERR_NONE) return {};
            const uint8_t *data = hunk.data() + (at % perHunk) * header->unitbytes + offset;
            return {data, data + USER_DATA};
        }
        frame += frames + (4 - frames % 4) % 4;
    }
}

}
