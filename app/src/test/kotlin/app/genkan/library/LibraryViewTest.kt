package app.genkan.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryViewTest {

    @Test
    fun `the toggle swaps shelf and grid`() {
        assertEquals(LibraryView.GRID, LibraryView.SHELF.toggled())
        assertEquals(LibraryView.SHELF, LibraryView.GRID.toggled())
    }

    @Test
    fun `the stored view reads back, and anything else is the shelf`() {
        LibraryView.entries.forEach { assertEquals(it, LibraryView.parse(it.name)) }
        assertEquals(LibraryView.SHELF, LibraryView.parse(null))
        assertEquals(LibraryView.SHELF, LibraryView.parse("CAROUSEL"))
    }
}
