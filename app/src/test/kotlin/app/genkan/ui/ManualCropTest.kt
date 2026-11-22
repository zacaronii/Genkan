package app.genkan.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualCropTest {
    @Test
    fun `a 16 to 9 band centred on the line`() {
        // A 1240 x 1754 page, the line at 40% down: 697 rows around row 701.
        assertEquals(353..1050, manualCrop(0.39f, 0.41f, 1240, 1754))
    }

    @Test
    fun `kept on the page at its top and bottom`() {
        assertEquals(0..697, manualCrop(0.01f, 0.02f, 1240, 1754))
        assertEquals(1057..1754, manualCrop(0.98f, 0.99f, 1240, 1754))
    }

    @Test
    fun `a page shorter than the band is taken whole`() {
        assertEquals(0..600, manualCrop(0.5f, 0.52f, 1240, 600))
    }
}
