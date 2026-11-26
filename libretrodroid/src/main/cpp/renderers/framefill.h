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

/**
 * Genkan: the size a hardware core's framebuffer is made, from the size asked for (the core's base
 * geometry, or a game's own) and the largest frame the core has drawn. A core may draw past its base
 * size (Flycast reports 640 x 480 and draws at its internal resolution, 3x that): a framebuffer of
 * the size asked for would crop it to its corner. So it grows to the largest frame, and a size asked
 * for later doesn't shrink it under one already drawn (a smaller frame fills its part of it). A core
 * that draws at its base size or under keeps the size asked for.
 */
inline std::pair<unsigned, unsigned> framebufferSize(unsigned askedWidth, unsigned askedHeight, int largestWidth, int largestHeight) {
    return {
        std::max(askedWidth, largestWidth > 0 ? (unsigned) largestWidth : 0U),
        std::max(askedHeight, largestHeight > 0 ? (unsigned) largestHeight : 0U)
    };
}

}

#endif //LIBRETRODROID_FRAMEFILL_H
