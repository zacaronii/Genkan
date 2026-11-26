package app.genkan.core

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

    @Test
    fun `kept hot, the game tries the lighter step and keeps it once the heat stops climbing`() {
        val learner = settled()
        val hot = full.copy(hottestC = 80)
        assertTrue(learner.feed(hot, HOT_WINDOW - 1).isEmpty())
        val tried = learner.second(hot)!!
        assertEquals(TuningLearner.Decision("light", keep = false, why = "trying light: the device ran hot"), tried)
        // Hot still, but no hotter than when the try started.
        val kept = learner.feed(hot.copy(hottestC = 79), TRIAL_SETTLE + WINDOW)
        assertEquals(listOf(TuningLearner.Decision("light", keep = true, why = "cooler at light")), kept)
        assertEquals("light", learner.step)
    }

    @Test
    fun `a lighter step that doesn't stop the heat climbing goes back and isn't tried again`() {
        val learner = settled()
        learner.feed(full.copy(hottestC = 80), HOT_WINDOW)
        val back = (1..TRIAL_SETTLE + WINDOW).mapNotNull { learner.second(full.copy(hottestC = 80 + it / 4)) }
        assertEquals("base", back.single().step)
        assertEquals("back to base: light didn't cool the device", back.single().why)
        // Hot again: light failed, and the risky step past it is never taken.
        assertTrue(learner.feed(full.copy(hottestC = 85), HOT_WINDOW * 3).isEmpty())
    }

    @Test
    fun `nothing better-looking is tried while hot, however much room`() {
        val learner = settled()
        val decisions = learner.feed(full.copy(hottestC = 80), ROOM * 2)
        assertFalse(decisions.any { it.step == "sharp" })
    }

    @Test
    fun `a lighter step taken only at start is remembered for the next start, once`() {
        val atStart = TuningLadder(
            steps = listOf(TuningStep("base", emptyMap(), live = false), TuningStep("cool", mapOf("k" to "c"), live = false)),
            defaultId = "base",
            defaults = mapOf("k" to "d"),
        )
        val learner = TuningLearner(atStart, "base").also { it.feed(full, SETTLE) }
        val decisions = learner.feed(full.copy(hottestC = 80), HOT_WINDOW * 4)
        assertEquals(
            listOf(TuningLearner.Decision("cool", keep = true, why = "cool from the next start: the device ran hot", nextStart = true)),
            decisions,
        )
        // The game keeps running where it was.
        assertEquals("base", learner.step)
    }
}
