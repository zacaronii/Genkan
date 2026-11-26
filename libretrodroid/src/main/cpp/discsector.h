/*
 *     Genkan's additions to LibretroDroid: one sector of a disc image's data track.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

#ifndef LIBRETRODROID_DISCSECTOR_H
#define LIBRETRODROID_DISCSECTOR_H

#include <cstdint>
#include <vector>

namespace libretrodroid {

/**
 * The user data (2048 bytes) of sector [sector] of the first data track of the CHD at [path], counted
 * from the track's start (after a pregap the image keeps, as a cue sheet's INDEX 01): what a disc's id
 * is read from (a PC Engine CD's boot block is its sector 1). Empty when the file isn't a CD CHD, has
 * no data track, or the sector isn't there.
 */
std::vector<uint8_t> chdDataSector(const char *path, uint32_t sector);

}

#endif //LIBRETRODROID_DISCSECTOR_H
