package app.genkan.core

import android.view.KeyEvent

/**
 * The pad's face buttons to the key codes the core is sent. A core wants positions: its A is the
 * right button and B the bottom one (on a PlayStation pad, circle and cross). The device names
 * its buttons by label, which depends on its controller layout, so Settings > Playing says which.
 */
object FaceButtons {

    /**
     * [keyCode] as sent to libretrodroid, which reads Android's A as the core's A. [aOnRight]: the
     * device's A is its right button (Nintendo layout); otherwise its bottom one (Xbox layout),
     * which is swapped into place. Every other key passes as it is.
     */
    fun map(keyCode: Int, aOnRight: Boolean): Int = if (aOnRight) keyCode else when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
        KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
        KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
        else -> keyCode
    }

    /** The layout a console's game is played with: the device's, turned over where that console's swap is on. */
    fun aOnRight(deviceAOnRight: Boolean, swapped: Boolean): Boolean = deviceAOnRight != swapped
}
