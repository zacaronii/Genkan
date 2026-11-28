package app.genkan.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TestModeTest {
    @get:Rule val folder = TemporaryFolder()

    private var now = 1_000_000L
    private fun mode() = TestMode(File(folder.root, TestMode.FILE), { now })

    @Test
    fun `off with no file, on once switched, read again by a new instance as after a restart`() {
        assertFalse(mode().active())
        mode().on(60_000)
        assertTrue(mode().active())
        assertEquals(1_060_000L, mode().until())
    }

    @Test
    fun `it ends by itself, and at most after twelve hours`() {
        mode().on(60_000)
        now += 60_000
        assertFalse(mode().active())
        mode().on(100 * TestMode.MAX_MS)
        assertEquals(now + TestMode.MAX_MS, mode().until())
    }

    @Test
    fun `switched off, or a file that doesn't read, is off`() {
        mode().on(60_000)
        mode().off()
        assertFalse(mode().active())
        assertNull(mode().until())
        File(folder.root, TestMode.FILE).writeText("soon")
        assertFalse(mode().active())
    }
}
