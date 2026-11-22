package app.genkan.ui

import app.genkan.core.ScreenLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LookPickerEntriesTest {
    @Test
    fun `every look appears once, under its family, in ScreenLook's order`() {
        val groups = LookPickerEntries.of()
        assertEquals(listOf("Plain", "CRT", "LCD", "Smoothing"), groups.map { it.first })
        assertEquals(ScreenLook.entries.sortedBy { it.family.ordinal }, groups.flatMap { it.second }.map { it.look })
    }

    @Test
    fun `a game's list starts with its console's look`() {
        val first = LookPickerEntries.of(console = ScreenLook.SHARP).first()
        assertNull(first.first)
        assertNull(first.second.single().look)
        assertEquals("Console's (Sharp)", first.second.single().label)
        assertEquals(ScreenLook.entries.size + 1, LookPickerEntries.of(console = ScreenLook.SHARP).sumOf { it.second.size })
    }

    @Test
    fun `the families hold what the page describes`() {
        assertEquals(ScreenLook.Family.CRT, ScreenLook.CRT_LOTTES.family)
        assertEquals(ScreenLook.Family.LCD, ScreenLook.DOT.family)
        assertEquals(ScreenLook.Family.PLAIN, ScreenLook.SHARP_BILINEAR.family)
        assertEquals(ScreenLook.Family.SMOOTHING, ScreenLook.CUT3.family)
    }
}
