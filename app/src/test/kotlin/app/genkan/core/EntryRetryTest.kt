package app.genkan.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EntryRetryTest {
    @Test
    fun `an answer at once is taken at once`() = runTest {
        var asked = 0
        assertEquals("entry", EntryRetry.retrying { asked++; "entry" })
        assertEquals(1, asked)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a fetch that fails first is asked again, and the later answer taken`() = runTest {
        var asked = 0
        // Fails twice (offline for a moment), then works: after 5 s and 15 s more.
        assertEquals("entry", EntryRetry.retrying { if (++asked < 3) null else "entry" })
        assertEquals(3, asked)
        assertEquals(20_000L, currentTime)
    }

    @Test
    fun `it gives up after three more tries, a minute on`() = runTest {
        var asked = 0
        assertNull(EntryRetry.retrying<String> { asked++; null })
        assertEquals(4, asked)
        assertEquals(60_000L, currentTime)
    }
}
