package app.genkan.core

import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.StickMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickMotionTest {

    @Test
    fun `the hat is the D-pad, the left stick the main stick, the right stick the second`() {
        val moves = StickMotion.moves(hatX = 0f, hatY = 0f, x = 0.9f, y = -0.2f, z = -0.5f, rz = 0.7f)
        assertEquals(
            listOf(
                StickMotion.Move(LibretroDroid.MOTION_SOURCE_DPAD, 0f, 0f),
                StickMotion.Move(LibretroDroid.MOTION_SOURCE_ANALOG_LEFT, 0.9f, -0.2f),
                StickMotion.Move(LibretroDroid.MOTION_SOURCE_ANALOG_RIGHT, -0.5f, 0.7f),
            ),
            moves,
        )
        // A stick pushed right moves no D-pad of its own accord.
        assertEquals(0f, moves.single { it.source == LibretroDroid.MOTION_SOURCE_DPAD }.x)
    }

    @Test
    fun `a GameCube's sticks are only sticks, other consoles' still steer the D-pad`() {
        val gamecube = CoreCatalog.forPlatform("ngc")!!
        assertTrue(gamecube.sticksOnly)
        // Handled: Android makes no D-pad presses of the stick, which a game may read as its menus.
        assertTrue(StickMotion.handled(gamecube.sticksOnly))
        CoreCatalog.cores.filter { it.id != "dolphin" }.forEach { core ->
            assertFalse(core.id, core.sticksOnly)
            assertFalse(core.id, StickMotion.handled(core.sticksOnly))
        }
    }
}
