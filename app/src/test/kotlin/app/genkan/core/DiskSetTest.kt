package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiskSetTest {
    @Test
    fun `a Disk System game's images are its disks' sides, and the next is the other side, then the next disk`() {
        val fds = DiskSet.of(intArrayOf(4, 0), sides = true)!!
        assertEquals("Disk 1, side A", fds.label(0))
        assertEquals("Disk 1, side B", fds.label(1))
        assertEquals("Disk 2, side A", fds.label(2))
        assertEquals(1, fds.next)
        assertEquals("1B", fds.short(1))
        assertEquals(0, fds.copy(current = 3).next)
    }

    @Test
    fun `discs of a set are numbered of the set, the last going round to the first`() {
        val dc = DiskSet.of(intArrayOf(3, 2), sides = false)!!
        assertEquals("Disc 3 of 3", dc.label(2))
        assertEquals("2/3", dc.short(1))
        assertEquals(0, dc.next)
    }

    @Test
    fun `one disk or none, nothing to swap`() {
        assertNull(DiskSet.of(intArrayOf(1, 0), sides = false))
        assertNull(DiskSet.of(IntArray(0), sides = true))
        assertNull(DiskSet.of(null, sides = false))
        // A current index past the end, from a core that says so, is held to the last.
        assertEquals(1, DiskSet.of(intArrayOf(2, 7), sides = false)!!.current)
    }
}
