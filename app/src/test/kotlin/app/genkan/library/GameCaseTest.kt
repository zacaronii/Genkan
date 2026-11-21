package app.genkan.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCaseTest {

    @Test
    fun `each console gets its own case`() {
        assertEquals(CaseStyle.JEWEL_CASE, GameCase.specFor("psx").style)
        assertEquals(CaseStyle.KEEP_CASE, GameCase.specFor("ps2").style)
        assertEquals(CaseStyle.KEEP_CASE, GameCase.specFor("psp").style)
        assertEquals(CaseStyle.CARD_CASE, GameCase.specFor("nds").style)
        assertEquals(CaseStyle.CARD_CASE, GameCase.specFor("3DS").style)
        assertEquals(CaseStyle.CARDBOARD_BOX, GameCase.specFor("sfc").style)
        assertEquals(CaseStyle.CARDBOARD_BOX, GameCase.specFor("famicom").style)
        assertEquals(GameCase.DEFAULT, GameCase.specFor("something-new"))
        assertEquals(GameCase.DEFAULT, GameCase.specFor(null))
    }

    @Test
    fun `cases have their consoles' proportions`() {
        assertEquals(1.0f, GameCase.specFor("psx").aspect, 0.001f)
        // The PSP's case is taller and narrower than the PS2's.
        assertTrue(GameCase.specFor("psp").aspect < GameCase.specFor("ps2").aspect)
        // DS cases are a little taller than wide; Super Famicom boxes lie on their side.
        assertTrue(GameCase.specFor("nds").aspect in 0.8f..1f)
        assertTrue(GameCase.specFor("sfc").aspect > 1.5f)
    }

    @Test
    fun `the real cover's proportions win within reason`() {
        val ps2 = GameCase.specFor("ps2")
        assertEquals(ps2.aspect, GameCase.aspectFor(ps2, null), 0.001f)
        assertEquals(0.68f, GameCase.aspectFor(ps2, 0.68f), 0.001f)
        // A full scan with spine and back is far too wide; it is held near a PS2 case's shape.
        assertEquals(ps2.aspect * GameCase.ASPECT_TOLERANCE, GameCase.aspectFor(ps2, 2.1f), 0.001f)
        assertEquals(ps2.aspect / GameCase.ASPECT_TOLERANCE, GameCase.aspectFor(ps2, 0.2f), 0.001f)
        assertEquals(ps2.aspect, GameCase.aspectFor(ps2, Float.NaN), 0.001f)
        assertEquals(ps2.aspect, GameCase.aspectFor(ps2, 0f), 0.001f)
    }

    @Test
    fun `tall cases fill the height and wide boxes the width`() {
        val (tallW, tallH) = GameCase.fit(0.7f, maxWidth = 300f, maxHeight = 200f)
        assertEquals(200f, tallH, 0.001f)
        assertEquals(140f, tallW, 0.001f)
        val (wideW, wideH) = GameCase.fit(1.8f, maxWidth = 300f, maxHeight = 200f)
        assertEquals(300f, wideW, 0.001f)
        assertEquals(300f / 1.8f, wideH, 0.001f)
    }
}
