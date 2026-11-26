// Genkan: where a touch leaves a core's pointer (RETRO_DEVICE_POINTER). Positions are the picture's,
// 0 to 1; a touch outside it, or a lifted finger, comes as a negative one.
#ifndef LIBRETRODROID_POINTER_H
#define LIBRETRODROID_POINTER_H

namespace libretrodroid {

struct PointerState {
    float x = -1;
    float y = -1;
    bool pressed = false;

    /**
     * A touch at [touchX], [touchY]. Off the picture the pointer is no longer pressed; with [keep]
     * it stays where the finger last was (a Wii's pointer: aimed, then left there while a button
     * is pressed), else it goes off the screen with the finger.
     */
    void touch(float touchX, float touchY, bool keep) {
        pressed = touchX >= 0 && touchY >= 0;
        if (pressed || !keep) {
            x = touchX;
            y = touchY;
        }
    }
};

}

#endif
