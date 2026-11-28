package app.genkan.ui

import app.genkan.core.ScreenLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun looks(groups: List<Pair<String?, List<LookChoice>>>) = groups.flatMap { it.second }.mapNotNull { it.look }

    @Test
    fun `each console's set`() {
        val plain = ScreenLook.entries.filter { it.family == ScreenLook.Family.PLAIN }.toSet()
        val psx = ScreenLook.suiting("psx")!!
        assertTrue(psx.containsAll(plain) && ScreenLook.CRT_LOTTES in psx && ScreenLook.CUT in psx)
        assertFalse(ScreenLook.LCD in psx || ScreenLook.DOT in psx)
        val gb = ScreenLook.suiting("gb")!!
        assertTrue(gb.containsAll(plain) && ScreenLook.DOT in gb && ScreenLook.LCD3X in gb && ScreenLook.CUT2 in gb)
        assertFalse(ScreenLook.CRT in gb || ScreenLook.LCD1X_NDS in gb || ScreenLook.LCD1X_PSP in gb)
        val nds = ScreenLook.suiting("nds")!!
        assertTrue(ScreenLook.LCD1X_NDS in nds && ScreenLook.LCD1X_PSP !in nds && ScreenLook.DOT !in nds)
        val psp = ScreenLook.suiting("psp")!!
        assertTrue(ScreenLook.LCD1X_PSP in psp && ScreenLook.LCD1X_NDS !in psp && ScreenLook.CUT !in psp && ScreenLook.CRT !in psp)
        // Every console's default is in its own set.
        for (p in listOf("psx", "gb", "gbc", "gba", "nds", "psp")) assertTrue(p, ScreenLook.defaultFor(p) in ScreenLook.suiting(p)!!)
    }

    @Test
    fun `a console not in the table gets every look, and no show-all row`() {
        assertNull(ScreenLook.suiting("pc-made-up"))
        assertEquals(ScreenLook.entries, looks(LookPickerEntries.of(suits = ScreenLook.suiting("pc-made-up"))).sortedBy { it.ordinal })
        assertFalse(LookPickerEntries.filters(null))
        assertTrue(LookPickerEntries.filters(ScreenLook.suiting("psx")))
    }

    @Test
    fun `the list shows the console's looks, the one in use even outside them, and all on show-all`() {
        val psx = ScreenLook.suiting("psx")
        val groups = LookPickerEntries.of(suits = psx, keep = setOf(ScreenLook.DOT))
        assertEquals(psx!! + ScreenLook.DOT, looks(groups).toSet())
        // A family with nothing left has no heading: only Dot of the LCD family, kept as the look in use.
        assertEquals(listOf(ScreenLook.DOT), groups.single { it.first == "LCD" }.second.map { it.look })
        assertEquals(listOf("Plain", "CRT", "Smoothing"), LookPickerEntries.of(suits = psx).map { it.first })
        assertEquals(ScreenLook.entries.size, looks(LookPickerEntries.of(suits = psx, showAll = true)).size)
    }

    @Test
    fun `every look kept stays listed`() {
        val shown = looks(LookPickerEntries.of(suits = ScreenLook.suiting("psx"), keep = setOf(ScreenLook.DOT, ScreenLook.LCD3X)))
        assertTrue(ScreenLook.DOT in shown && ScreenLook.LCD3X in shown && ScreenLook.LCD !in shown)
    }
}
