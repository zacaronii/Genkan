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

    /** The owner's library: Japanese releases under RomM's western slugs. */
    @Test
    fun `the Japanese library's consoles`() {
        fun check(slug: String, style: CaseStyle, aspect: Float) {
            val spec = GameCase.specFor(slug)
            assertEquals(slug, style, spec.style)
            assertEquals(slug, aspect, spec.aspect, 0.001f)
        }
        // Consoles with games.
        check("psx", CaseStyle.JEWEL_CASE, 1.0f)
        check("ps2", CaseStyle.KEEP_CASE, 0.71f)
        check("nds", CaseStyle.CARD_CASE, 0.89f)
        check("3ds", CaseStyle.CARD_CASE, 0.89f)
        check("psp", CaseStyle.KEEP_CASE, 0.59f)
        check("snes", CaseStyle.CARDBOARD_BOX, 1.8f)
        check("fds", CaseStyle.CARDBOARD_BOX, 1.42f)
        // Consoles whose case changed for Japanese packaging.
        check("nes", CaseStyle.CARDBOARD_BOX, 1.42f)
        check("tg16", CaseStyle.JEWEL_CASE, 1.0f)
        check("turbografx-cd", CaseStyle.JEWEL_CASE, 1.0f)
        check("gba", CaseStyle.CARDBOARD_BOX, 1.55f)
        check("gamegear", CaseStyle.CARDBOARD_BOX, 0.75f)
        check("genesis", CaseStyle.KEEP_CASE, 0.72f)
        // Japanese DS cases are black; the 3DS keeps the white one.
        assertEquals(0xFF1E1E22, GameCase.specFor("nds").color)
        assertEquals(0xFFF0F0F2, GameCase.specFor("3ds").color)
    }

    @Test
    fun `cardboard boxes follow the cover within fixed bounds`() {
        val famicom = GameCase.specFor("nes")
        // A tall Famicom box is kept tall, well past a plastic case's tolerance.
        assertEquals(0.7f, GameCase.aspectFor(famicom, 0.7f), 0.001f)
        assertEquals(1.85f, GameCase.aspectFor(famicom, 1.85f), 0.001f)
        assertEquals(GameCase.BOX_MIN_ASPECT, GameCase.aspectFor(famicom, 0.3f), 0.001f)
        assertEquals(GameCase.BOX_MAX_ASPECT, GameCase.aspectFor(famicom, 3f), 0.001f)
        assertEquals(famicom.aspect, GameCase.aspectFor(famicom, null), 0.001f)
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
