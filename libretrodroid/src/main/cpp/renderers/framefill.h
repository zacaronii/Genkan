#ifndef LIBRETRODROID_FRAMEFILL_H
#define LIBRETRODROID_FRAMEFILL_H

#include <algorithm>
#include <utility>

namespace libretrodroid {

/**
 * Genkan: the part of a texture a frame filled, as fractions of its width and height. A
 * hardware-rendered core may draw a frame smaller than its framebuffer (a PS2 game's 512 columns of
 * a 640-wide one), from the origin; a frame as large or larger fills all of it, and an unknown size
 * counts as all of it.
 */
inline std::pair<float, float> filledFraction(int frameWidth, int frameHeight, unsigned textureWidth, unsigned textureHeight) {
    if (textureWidth == 0 || textureHeight == 0 || frameWidth <= 0 || frameHeight <= 0) return {1.0F, 1.0F};
    return {
        std::min(1.0F, (float) frameWidth / (float) textureWidth),
        std::min(1.0F, (float) frameHeight / (float) textureHeight)
    };
}

}

#endif //LIBRETRODROID_FRAMEFILL_H
