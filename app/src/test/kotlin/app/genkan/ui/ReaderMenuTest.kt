package app.genkan.ui

import app.genkan.manual.ManualFetch
import app.genkan.ppsspp.HookFinderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMenuTest {
    private fun actions(state: ReaderBarState) = ReaderMenu.items(state).map { it.action }

    @Test
    fun `with nothing to offer the menu is empty`() {
        assertEquals(emptyList<ReaderAction>(), actions(ReaderBarState()))
    }

    @Test
    fun `OCR's area and engine come with OCR on, sentence audio until it's allowed`() {
        assertEquals(emptyList<ReaderAction>(), actions(ReaderBarState(ocrOn = false, capturing = true, recording = true)))
        assertEquals(
            listOf(ReaderAction.OCR_AREA, ReaderAction.OCR_ENGINE, ReaderAction.AUDIO),
            actions(ReaderBarState(ocrOn = true, capturing = true, engine = "ML Kit")),
        )
        val engine = ReaderMenu.items(ReaderBarState(ocrOn = true, engine = "ML Kit")).single()
        assertEquals("ML Kit", engine.state)
        assertFalse(engine.closes)
    }

    @Test
    fun `rows show their state, and what opens something closes the menu`() {
        val items = ReaderMenu.items(ReaderBarState(overlayAvailable = true, overlayOn = true, look = "CRT" to true, thread = "2"))
        assertEquals(listOf("On", "CRT", "2"), items.map { it.state })
        assertEquals(listOf(false, true, true), items.map { it.closes })
        assertEquals("Off", ReaderMenu.items(ReaderBarState(overlayAvailable = true)).single().state)
    }

    @Test
    fun `a look not kept, a hook finder's result and a failed manual want a look`() {
        assertTrue(ReaderMenu.items(ReaderBarState(look = "CRT" to false)).any { it.action == ReaderAction.KEEP_LOOK && it.attention })
        assertTrue(ReaderMenu.items(ReaderBarState(finder = HookFinderState.Found("found"))).single().attention)
        assertTrue(ReaderMenu.items(ReaderBarState(finder = HookFinderState.Failed("none"))).single().attention)
        assertTrue(ReaderMenu.items(ReaderBarState(manual = true, manualFetch = ManualFetch.Failed("no"))).single().attention)
        val quiet = ReaderBarState(manual = true, look = "CRT" to true, finder = HookFinderState.Working("looking"), canShowGame = true)
        assertFalse(ReaderMenu.items(quiet).any { it.attention })
    }

    @Test
    fun `a manual being fetched shows how far and can't be pressed`() {
        val row = ReaderMenu.items(ReaderBarState(manual = true, manualFetch = ManualFetch.Busy(0.42f))).single()
        assertEquals("Fetching 42%", row.state)
        assertFalse(row.enabled)
    }

    @Test
    fun `the hook finder offers a search only for a game without a hook`() {
        assertEquals(emptyList<ReaderAction>(), actions(ReaderBarState()))
        assertEquals(listOf(ReaderAction.FIND_HOOK), actions(ReaderBarState(canFindHook = true)))
        assertEquals(listOf(ReaderAction.STOP_HOOK), actions(ReaderBarState(canFindHook = true, finder = HookFinderState.Working("…"))))
    }
}
