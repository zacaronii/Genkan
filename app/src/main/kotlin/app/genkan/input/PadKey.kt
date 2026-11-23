package app.genkan.input

import android.view.KeyEvent

/** The buttons Genkan's screens react to, whatever the controller or keyboard sends. */
enum class PadKey {
    UP, DOWN, LEFT, RIGHT, A, B, X, Y, L1, R1, L2, R2, START, SELECT,
    /** The left stick's click. */
    L3,
    /** The right stick's click. */
    R3;

    companion object {
        fun from(keyCode: Int): PadKey? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> A
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> B
            KeyEvent.KEYCODE_BUTTON_X -> X
            KeyEvent.KEYCODE_BUTTON_Y -> Y
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> L1
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> R1
            KeyEvent.KEYCODE_BUTTON_L2 -> L2
            KeyEvent.KEYCODE_BUTTON_R2 -> R2
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> START
            KeyEvent.KEYCODE_BUTTON_SELECT -> SELECT
            KeyEvent.KEYCODE_BUTTON_THUMBL -> L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> R3
            else -> null
        }
    }
}
