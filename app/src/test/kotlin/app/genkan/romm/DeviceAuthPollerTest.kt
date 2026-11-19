package app.genkan.romm

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAuthPollerTest {

    private fun scripted(vararg results: DeviceAuthPoll): suspend () -> DeviceAuthPoll {
        val queue = ArrayDeque(results.toList())
        return { queue.removeFirstOrNull() ?: DeviceAuthPoll.Pending }
    }

    @Test
    fun `pending polls continue until approval`() = runTest {
        val outcome = pollDeviceAuthUntilResolved(5, 600, scripted(DeviceAuthPoll.Pending, DeviceAuthPoll.SlowDown, DeviceAuthPoll.Approved("t")))
        assertEquals(DeviceAuthOutcome.Approved("t"), outcome)
    }

    @Test
    fun `a denial ends the flow at once`() = runTest {
        assertEquals(DeviceAuthOutcome.Denied, pollDeviceAuthUntilResolved(5, 600, scripted(DeviceAuthPoll.Denied)))
    }

    @Test
    fun `transient failures are retried, then given up on`() = runTest {
        val recovers = pollDeviceAuthUntilResolved(
            5, 600, scripted(DeviceAuthPoll.Failed("502"), DeviceAuthPoll.Failed("502"), DeviceAuthPoll.Approved("t"))
        )
        assertEquals(DeviceAuthOutcome.Approved("t"), recovers)
        val fails = pollDeviceAuthUntilResolved(5, 600) { DeviceAuthPoll.Failed("502") }
        assertEquals(DeviceAuthOutcome.Failed("502"), fails)
    }

    @Test
    fun `a non-retryable failure stops immediately`() = runTest {
        val outcome = pollDeviceAuthUntilResolved(5, 600, scripted(DeviceAuthPoll.Failed("consumed", retryable = false)))
        assertEquals(DeviceAuthOutcome.Failed("consumed"), outcome)
    }
}
