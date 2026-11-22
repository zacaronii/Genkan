package app.genkan.library

import org.junit.Assert.assertEquals
import org.junit.Test

class InsertTimelineTest {

    private val builtIn = InsertTimeline.forLaunch(1_500, builtIn = true)
    private val external = InsertTimeline.forLaunch(1_500, builtIn = false)

    @Test
    fun `the animation's length follows the setting and the animator scale`() {
        assertEquals(1_500L, InsertTimeline.animationMs(enabled = true, animatorScale = 1f))
        assertEquals(750L, InsertTimeline.animationMs(enabled = true, animatorScale = 0.5f))
        assertEquals(3_000L, InsertTimeline.animationMs(enabled = true, animatorScale = 2f))
        // Animations off in the system, or switched off in Genkan: none at all.
        assertEquals(0L, InsertTimeline.animationMs(enabled = true, animatorScale = 0f))
        assertEquals(0L, InsertTimeline.animationMs(enabled = false, animatorScale = 1f))
        assertEquals(0L, InsertTimeline.animationMs(enabled = true, animatorScale = Float.NaN))
    }

    @Test
    fun `ready before the animation ends, the built-in player starts at the end`() {
        assertEquals(1_500L, builtIn.launchAt(readyAtMs = 300, skippedAtMs = null))
        assertEquals(1_500L, builtIn.cutAt(readyAtMs = 300, skippedAtMs = null))
    }

    @Test
    fun `ready before the animation ends, an emulator app starts ahead of the end`() {
        assertEquals(1_500L - InsertTimeline.EXTERNAL_LEAD_MS, external.launchAt(readyAtMs = 300, skippedAtMs = null))
        // The animation still plays to its end while the emulator's window comes up.
        assertEquals(1_500L, external.cutAt(readyAtMs = 300, skippedAtMs = null))
    }

    @Test
    fun `ready after the animation ends, the game starts the moment it is ready`() {
        assertEquals(4_200L, builtIn.launchAt(readyAtMs = 4_200, skippedAtMs = null))
        assertEquals(4_200L, builtIn.cutAt(readyAtMs = 4_200, skippedAtMs = null))
        assertEquals(4_200L, external.launchAt(readyAtMs = 4_200, skippedAtMs = null))
        assertEquals(4_200L, external.cutAt(readyAtMs = 4_200, skippedAtMs = null))
    }

    @Test
    fun `a skip ends the animation, and the game starts as soon as it is ready`() {
        assertEquals(500L, builtIn.animationEnd(skippedAtMs = 500))
        assertEquals(500L, builtIn.launchAt(readyAtMs = 200, skippedAtMs = 500))
        assertEquals(900L, builtIn.launchAt(readyAtMs = 900, skippedAtMs = 500))
        // Skipped before the lead: an emulator app starts straight away, with nothing to wait for.
        assertEquals(200L, external.launchAt(readyAtMs = 200, skippedAtMs = 300))
        assertEquals(300L, external.cutAt(readyAtMs = 200, skippedAtMs = 300))
        // A skip after the animation has already ended changes nothing.
        assertEquals(1_500L, builtIn.launchAt(readyAtMs = 100, skippedAtMs = 2_000))
    }

    @Test
    fun `with no animation, nothing waits`() {
        val none = InsertTimeline.forLaunch(0, builtIn = false)
        assertEquals(0L, none.leadMs)
        assertEquals(250L, none.launchAt(readyAtMs = 250, skippedAtMs = null))
        assertEquals(250L, none.cutAt(readyAtMs = 250, skippedAtMs = null))
        assertEquals(0L, none.launchAt(readyAtMs = 0, skippedAtMs = null))
    }

    @Test
    fun `only a fresh press of a pad button skips`() {
        assertEquals(InsertKey.SKIP, InsertKey.decide(showing = true, isPadKey = true, repeatCount = 0))
        // Holding A, which started the game, repeats from about 400 ms: used up, never a skip.
        assertEquals(InsertKey.CONSUME, InsertKey.decide(showing = true, isPadKey = true, repeatCount = 1))
        assertEquals(InsertKey.CONSUME, InsertKey.decide(showing = true, isPadKey = true, repeatCount = 30))
        // Volume and other keys that aren't the pad's go on as usual.
        assertEquals(InsertKey.PASS, InsertKey.decide(showing = true, isPadKey = false, repeatCount = 0))
        assertEquals(InsertKey.PASS, InsertKey.decide(showing = true, isPadKey = false, repeatCount = 5))
        // No animation: the library has every key.
        assertEquals(InsertKey.PASS, InsertKey.decide(showing = false, isPadKey = true, repeatCount = 0))
        assertEquals(InsertKey.PASS, InsertKey.decide(showing = false, isPadKey = true, repeatCount = 3))
    }

    @Test
    fun `the lead never exceeds a short animation`() {
        val short = InsertTimeline.forLaunch(300, builtIn = false)
        assertEquals(300L, short.leadMs)
        assertEquals(50L, short.launchAt(readyAtMs = 50, skippedAtMs = null))
    }
}
