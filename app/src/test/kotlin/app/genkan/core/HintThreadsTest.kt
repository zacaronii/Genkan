package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HintThreadsTest {
    private fun busy(tid: Int, share: Double) = ThreadLoad.Busy(tid, "t$tid", share)

    @Test
    fun `the emulation thread and the busy workers`() {
        assertEquals(setOf(1, 7, 8), HintThreads.choose(1, listOf(busy(7, 0.4), busy(8, 0.2), busy(9, 0.05)), emptySet()))
    }

    @Test
    fun `nothing changes while the same workers are busy, or quieter but still running`() {
        assertNull(HintThreads.choose(1, listOf(busy(7, 0.4), busy(8, 0.2)), setOf(1, 7, 8)))
        assertNull(HintThreads.choose(1, listOf(busy(7, 0.4), busy(8, 0.02)), setOf(1, 7, 8)))
    }

    @Test
    fun `a worker that stopped is dropped, a newly busy one added, at most four`() {
        assertEquals(setOf(1, 7, 10), HintThreads.choose(1, listOf(busy(7, 0.4), busy(10, 0.3)), setOf(1, 7, 8)))
        val many = (10..20).map { busy(it, 0.5) }
        assertEquals(1 + HintThreads.MAX_WORKERS, HintThreads.choose(1, many, emptySet())!!.size)
    }

    @Test
    fun `no session before the emulation thread is known`() {
        assertNull(HintThreads.choose(null, listOf(busy(7, 0.9)), emptySet()))
    }

    @Test
    fun `the tuning file turns parts off, and reads back what it wrote`() {
        val off = TuningSwitches(hints = false, frameRate = true, gameState = false, priority = false)
        assertEquals(off, TuningSwitches.parse(off.toJson()))
        assertEquals(TuningSwitches(), TuningSwitches.parse("{}"))
        // A forced Vulkan failure: 1 the probe, 2 the device, 3 a lost device (a larger number is 3).
        assertEquals(3, TuningSwitches.parse(TuningSwitches(vulkanFailure = 3).toJson()).vulkanFailure)
        assertEquals(3, TuningSwitches.parse("""{"vulkan_failure": 9}""").vulkanFailure)
    }
}
