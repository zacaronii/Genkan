package com.swordfish.libretrodroid

/**
 * Genkan: where a gamepad's motion goes. The hat is the D-pad, the X/Y axes the left stick and
 * Z/RZ the right stick, each to its own libretro input. A console with sticks of its own (a
 * GameCube) takes the motion whole: handled, Android doesn't also turn an unhandled stick into
 * D-pad key presses, which such a game reads as the D-pad (menus, in some). Other consoles
 * leave it unhandled, so the left stick still steers a D-pad-only game.
 */
object StickMotion {
    /** One motion source's x and y. */
    data class Move(val source: Int, val x: Float, val y: Float)

    fun moves(hatX: Float, hatY: Float, x: Float, y: Float, z: Float, rz: Float): List<Move> = listOf(
        Move(LibretroDroid.MOTION_SOURCE_DPAD, hatX, hatY),
        Move(LibretroDroid.MOTION_SOURCE_ANALOG_LEFT, x, y),
        Move(LibretroDroid.MOTION_SOURCE_ANALOG_RIGHT, z, rz),
    )

    /** Whether a gamepad's motion is handled here, so Android makes no D-pad presses of it. */
    fun handled(sticksOnly: Boolean): Boolean = sticksOnly
}
