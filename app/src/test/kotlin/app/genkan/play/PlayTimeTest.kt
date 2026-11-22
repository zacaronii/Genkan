package app.genkan.play

import app.genkan.play.UsageKind.PAUSED
import app.genkan.play.UsageKind.RESUMED
import app.genkan.play.UsageKind.SCREEN_OFF
import app.genkan.play.UsageKind.STOPPED
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayTimeTest {

    private val emu = "org.dolphinemu.dolphinemu"
    private fun e(t: Long, kind: UsageKind, pkg: String? = emu, cls: String? = "Emu") = UsageSample(t, kind, pkg, cls)

    @Test
    fun `without usage access the session runs from launch to now`() {
        assertEquals(Measured(9_000, 8_000), PlayTime.measure(1_000, 9_000, emu, null))
    }

    @Test
    fun `one stretch in front ends when the emulator pauses`() {
        val m = PlayTime.measure(0, 100_000, emu, listOf(e(1_000, RESUMED), e(61_000, PAUSED)))
        assertEquals(Measured(61_000, 60_000), m)
    }

    @Test
    fun `hopping between the emulator's own activities is one stretch`() {
        val events = listOf(
            e(1_000, RESUMED, cls = "Main"),
            e(5_000, RESUMED, cls = "Game"),
            e(5_100, PAUSED, cls = "Main"),
            e(41_000, STOPPED, cls = "Game"),
        )
        assertEquals(Measured(41_000, 40_000), PlayTime.measure(0, 100_000, emu, events))
    }

    @Test
    fun `sleep and other apps are left out`() {
        val events = listOf(
            e(0, RESUMED),
            e(10_000, SCREEN_OFF, pkg = null, cls = null),
            e(70_000, RESUMED),
            e(80_000, PAUSED),
            e(80_000, RESUMED, pkg = "com.android.settings"),
            e(90_000, RESUMED),
            e(95_000, PAUSED),
        )
        assertEquals(Measured(95_000, 25_000), PlayTime.measure(0, 200_000, emu, events))
    }

    @Test
    fun `an emulator still in front is counted up to now`() {
        assertEquals(Measured(50_000, 40_000), PlayTime.measure(0, 50_000, emu, listOf(e(10_000, RESUMED))))
    }

    @Test
    fun `an emulator that never came to the front played for zero`() {
        assertEquals(Measured(0, 0), PlayTime.measure(0, 50_000, emu, emptyList()))
    }

    @Test
    fun `events outside the session are ignored`() {
        val events = listOf(e(-5_000, RESUMED), e(1_000, RESUMED), e(11_000, PAUSED), e(60_000, RESUMED))
        assertEquals(Measured(11_000, 10_000), PlayTime.measure(0, 50_000, emu, events))
    }

    @Test
    fun `with classes, only those activities of the package count`() {
        // The built-in player: Genkan's launcher resumes for a moment mid-game, the game's own
        // activity is stopped and resumed again (a new instance), then the game ends.
        val events = listOf(
            e(1_000, RESUMED, pkg = "app.genkan", cls = "Core"),
            e(10_000, RESUMED, pkg = "app.genkan", cls = "Home"),
            e(10_000, STOPPED, pkg = "app.genkan", cls = "Core"),
            e(10_100, PAUSED, pkg = "app.genkan", cls = "Home"),
            e(10_100, RESUMED, pkg = "app.genkan", cls = "Core"),
            e(55_000, PAUSED, pkg = "app.genkan", cls = "Core"),
            e(55_000, RESUMED, pkg = "app.genkan", cls = "Home"),
        )
        assertEquals(Measured(55_000, 53_900), PlayTime.measure(0, 90_000, "app.genkan", events, setOf("Core")))
        // Without the filter the launcher's time after the game counts too.
        assertEquals(Measured(90_000, 89_000), PlayTime.measure(0, 90_000, "app.genkan", events))
    }
}
