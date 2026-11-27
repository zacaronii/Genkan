package app.genkan.core

import app.genkan.core.TuningLearner.Companion.CEILING_WINDOW
import app.genkan.core.TuningLearner.Companion.CLOCK_BASE
import app.genkan.core.TuningLearner.Companion.HOT_WINDOW
import app.genkan.core.TuningLearner.Companion.ROOM
import app.genkan.core.TuningLearner.Companion.SETTLE
import app.genkan.core.TuningLearner.Companion.TRIAL_SETTLE
import app.genkan.core.TuningLearner.Companion.WINDOW
import app.genkan.core.TuningLearner.Second
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningLearnerTest {
    // A made-up ladder: two live safe steps around the default, then a risky one.
    private val ladder = TuningLadder(
        steps = listOf(
            TuningStep("sharp", mapOf("k" to "s")),
            TuningStep("base", emptyMap()),
            TuningStep("light", mapOf("k" to "l")),
            TuningStep("risky", mapOf("k" to "r"), risky = true),
        ),
        defaultId = "base",
        defaults = mapOf("k" to "d"),
    )

    private val full = Second(1.0, busiest = 0.3, gpuBusyPercent = 20, hottestC = 50)
    private val busy = Second(1.0, busiest = 0.8, gpuBusyPercent = 20, hottestC = 50)
    private fun slow(speed: Double) = Second(speed, busiest = 0.5, gpuBusyPercent = 60, hottestC = 50)

    /** Feeds [s] [n] times; the decisions made along the way. */
    private fun TuningLearner.feed(s: Second, n: Int) = (1..n).mapNotNull { second(s) }

    private fun settled(start: String = "base") = TuningLearner(ladder, start).also { it.feed(full.copy(busiest = 0.9), SETTLE) }

    @Test
    fun `nothing is tried while the game loads`() {
        val learner = TuningLearner(ladder, "base")
        assertTrue(learner.feed(slow(0.5), SETTLE).isEmpty())
    }

    @Test
    fun `a game with room tries the better-looking step and keeps it while full speed holds`() {
        val learner = settled()
        val tried = learner.feed(full, ROOM)
        assertEquals(listOf(TuningLearner.Decision("sharp", keep = false, why = "trying sharp: the game had room")), tried)
        val kept = learner.feed(full, TRIAL_SETTLE + WINDOW)
        assertEquals("sharp", kept.single().step)
        assertTrue(kept.single().keep)
        assertEquals("sharp", learner.step)
    }

    @Test
    fun `full speed is on average, so a jittery second doesn't spoil the room but a second behind does`() {
        val learner = settled()
        val jittery = (1..ROOM).mapNotNull { learner.second(if (it % 15 == 0) full.copy(speed = 0.98) else full.copy(speed = 1.0)) }
        assertEquals("sharp", jittery.single().step)

        val behind = settled()
        assertTrue((1..ROOM).mapNotNull { behind.second(if (it == ROOM / 2) full.copy(speed = 0.95) else full) }.isEmpty())
    }

    @Test
    fun `room means a light thread and GPU too, not only full speed`() {
        assertTrue(settled().feed(busy, ROOM * 2).isEmpty())
        assertTrue(settled().feed(full.copy(gpuBusyPercent = 70), ROOM * 2).isEmpty())
    }

    @Test
    fun `a better-looking step that slows the game goes back at once, and isn't tried again`() {
        val learner = settled()
        learner.feed(full, ROOM)
        learner.feed(full, TRIAL_SETTLE)
        val back = learner.second(slow(0.85))!!
        assertEquals("base", back.step)
        assertFalse(back.keep)
        assertEquals("base", learner.step)
        assertTrue(learner.feed(full, ROOM * 2).isEmpty())
    }

    @Test
    fun `a hitch alone doesn't end a better-looking try, but a slower window does`() {
        val learner = settled()
        learner.feed(full, ROOM + TRIAL_SETTLE)
        assertNull(learner.second(slow(0.95)))
        val judged = learner.feed(slow(0.97), WINDOW)
        assertEquals("base", judged.single().step)
        assertFalse(judged.single().keep)
    }

    @Test
    fun `a game behind tries the next lighter step and keeps it when it helps`() {
        val learner = settled()
        val tried = learner.feed(slow(0.85), WINDOW)
        assertEquals("light", tried.single().step)
        val kept = learner.feed(slow(0.95), TRIAL_SETTLE + WINDOW)
        assertEquals(TuningLearner.Decision("light", keep = true, why = "faster at light"), kept.single())
    }

    @Test
    fun `a lighter step that doesn't help enough goes back, and the risky one past it is never tried`() {
        val learner = settled()
        learner.feed(slow(0.85), WINDOW)
        val back = learner.feed(slow(0.86), TRIAL_SETTLE + WINDOW)
        assertEquals("base", back.single().step)
        assertFalse(back.single().keep)
        assertTrue(learner.feed(slow(0.85), WINDOW * 3).isEmpty())

        val atLight = settled("light")
        assertTrue(atLight.feed(slow(0.8), WINDOW * 3).isEmpty())
    }

    @Test
    fun `a step that waits for a restart is never tried as the game runs`() {
        val restartOnly = ladder.copy(steps = ladder.steps.map { if (it.id == "sharp") it.copy(live = false) else it })
        val learner = TuningLearner(restartOnly, "base").also { it.feed(busy, SETTLE) }
        assertTrue(learner.feed(full, ROOM * 2).isEmpty())
    }

    @Test
    fun `nothing is tried hot, and a try under way goes back`() {
        val learner = settled()
        assertTrue(learner.feed(slow(0.8).copy(hottestC = 80), HOT_WINDOW - 1).isEmpty())
        learner.feed(slow(0.85), WINDOW)
        val back = learner.second(slow(0.9).copy(hottestC = 80))!!
        assertEquals("base", back.step)
    }

    @Test
    fun `a pause starts the windows again`() {
        val learner = settled()
        learner.feed(full, ROOM - 1)
        learner.paused()
        assertTrue(learner.feed(full, ROOM - 1).isEmpty())
    }

    /** Settled, with the fastest core at [mhz] through the session's first minute: the clock it counts as its own. */
    private fun clocked(mhz: Int = 3000, ladder: TuningLadder = this.ladder) =
        TuningLearner(ladder, ladder.defaultId).also { it.feed(full.copy(busiest = 0.9, fastestMhz = mhz), SETTLE + CLOCK_BASE) }

    @Test
    fun `heat alone, with full speed and the clock held, changes nothing`() {
        // As on the Thor's 20-minute round: around 85 °C for long, the clock and speed held.
        val learner = clocked()
        assertTrue(learner.feed(full.copy(hottestC = 86, fastestMhz = 3000), 20 * 60).isEmpty())
        assertEquals("base", learner.step)
    }

    @Test
    fun `hot with the clock falling, the game tries the lighter step and keeps it once the heat stops climbing`() {
        val learner = clocked()
        val throttled = full.copy(hottestC = 85, fastestMhz = 2500)
        assertTrue(learner.feed(throttled, HOT_WINDOW - 1).isEmpty())
        val tried = learner.second(throttled)!!
        assertEquals(TuningLearner.Decision("light", keep = false, why = "trying light: the device ran hot and its clock fell"), tried)
        val kept = learner.feed(throttled.copy(hottestC = 84), TRIAL_SETTLE + WINDOW)
        assertEquals(listOf(TuningLearner.Decision("light", keep = true, why = "cooler at light")), kept)
        assertEquals("light", learner.step)
    }

    @Test
    fun `the learner, on a PS2 game played on Software or GL, never takes it back to Vulkan`() {
        // On Vulkan itself it doesn't run (CoreActivity): it would move a hot game down to Software.
        val lrps2 = TuningLadders.of("lrps2")!!
        val learner = TuningLearner(lrps2, "software").also { it.feed(full.copy(busiest = 0.9, fastestMhz = 3000), SETTLE + CLOCK_BASE) }
        val decisions = learner.feed(full.copy(hottestC = 85, fastestMhz = 2500), HOT_WINDOW * 4) +
            learner.feed(slow(0.8), WINDOW * 4) + learner.feed(full, WINDOW * 8)
        assertTrue(decisions.isNotEmpty())
        assertTrue(decisions.none { lrps2.step(it.step)!!.vulkan })
    }

    @Test
    fun `a clock that only dips between busy moments isn't throttling`() {
        // The governor idles the fastest core between frames; its highest second in the window counts.
        val learner = clocked()
        val decisions = (1..HOT_WINDOW * 5).mapNotNull { learner.second(full.copy(hottestC = 85, fastestMhz = if (it % 3 == 0) 3000 else 1900)) }
        assertTrue(decisions.isEmpty())
    }

    @Test
    fun `hot and behind, the game tries the lighter step`() {
        val learner = clocked()
        val decisions = learner.feed(slow(0.93).copy(hottestC = 80, fastestMhz = 3000), HOT_WINDOW)
        assertEquals("trying light: the device ran hot and the game fell behind", decisions.single().why)
    }

    @Test
    fun `a lighter step that doesn't stop the heat climbing goes back and isn't tried again`() {
        val learner = clocked()
        learner.feed(full.copy(hottestC = 85, fastestMhz = 2500), HOT_WINDOW)
        val back = (1..TRIAL_SETTLE + WINDOW).mapNotNull { learner.second(full.copy(hottestC = 85 + it / 4, fastestMhz = 2500)) }
        assertEquals("base", back.single().step)
        assertEquals("back to base: light didn't cool the device", back.single().why)
        // Throttled again: light failed, and the risky step past it is never taken.
        assertTrue(learner.feed(full.copy(hottestC = 85, fastestMhz = 2500), HOT_WINDOW * 3).isEmpty())
    }

    @Test
    fun `at the ceiling for a minute the game steps down whatever it costs, but not for spikes`() {
        val learner = clocked()
        // Spikes to 92 °C between cooler seconds, as on the Thor: never a minute straight.
        assertTrue((1..CEILING_WINDOW * 5).mapNotNull { learner.second(full.copy(hottestC = if (it % 2 == 0) 92 else 88, fastestMhz = 3000)) }.isEmpty())
        assertTrue(learner.feed(full.copy(hottestC = 80, fastestMhz = 3000), 1).isEmpty())
        val ceiling = full.copy(hottestC = 92, fastestMhz = 3000)
        assertTrue(learner.feed(ceiling, CEILING_WINDOW - 1).isEmpty())
        assertEquals("trying light: the device stayed at 92 °C", learner.second(ceiling)!!.why)
    }

    @Test
    fun `nothing better-looking is tried while hot, however much room`() {
        val learner = clocked()
        val decisions = learner.feed(full.copy(hottestC = 80, fastestMhz = 3000), ROOM * 2)
        assertTrue(decisions.isEmpty())
    }

    @Test
    fun `a lighter step taken only at start is remembered for the next start, once`() {
        val atStart = TuningLadder(
            steps = listOf(TuningStep("base", emptyMap(), live = false), TuningStep("cool", mapOf("k" to "c"), live = false)),
            defaultId = "base",
            defaults = mapOf("k" to "d"),
        )
        val learner = clocked(ladder = atStart)
        val decisions = learner.feed(full.copy(hottestC = 93, fastestMhz = 3000), CEILING_WINDOW * 4)
        assertEquals(
            listOf(TuningLearner.Decision("cool", keep = true, why = "cool from the next start: the device stayed at 92 °C", nextStart = true)),
            decisions,
        )
        // The game keeps running where it was.
        assertEquals("base", learner.step)
    }
}
