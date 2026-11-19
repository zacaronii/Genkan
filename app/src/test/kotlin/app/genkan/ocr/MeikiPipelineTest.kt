package app.genkan.ocr

import app.genkan.ocr.MeikiPipeline.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeikiPipelineTest {

    @Test
    fun `inputs are planar BGR from 0 to 1 in the top-left corner`() {
        // One red pixel, one blue: ARGB 0xFFFF0000 and 0xFF0000FF.
        val pixels = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt())
        val input = MeikiPipeline.recInput(pixels, 2, 1, vertical = false)
        val plane = MeikiPipeline.REC_H_W * MeikiPipeline.REC_H_H
        assertEquals(3 * plane, input.size)
        assertEquals(0f, input[0], 0f)          // B of red
        assertEquals(1f, input[2 * plane], 0f)  // R of red
        assertEquals(1f, input[1], 0f)          // B of blue
        assertEquals(0f, input[2 * plane + 1], 0f)
        assertEquals(0f, input[MeikiPipeline.REC_H_W], 0f) // second row is padding
    }

    @Test
    fun `detection letterboxes and maps boxes back to the image`() {
        val scale = MeikiPipeline.detScale(1920, 300)
        assertEquals(0.5f, scale, 0f)
        // Two near-copies of one line and a weak box: one line survives, in image pixels.
        val boxes = floatArrayOf(10f, 20f, 400f, 50f, 11f, 20f, 401f, 50f, 0f, 0f, 30f, 30f)
        val scores = floatArrayOf(0.9f, 0.85f, 0.2f)
        val lines = MeikiPipeline.lines(boxes, scores, scale, 1920, 300)
        assertEquals(listOf(Box(20, 40, 800, 100)), lines.map { it.box })
    }

    @Test
    fun `crops are padded before the line and across it, never along its end`() {
        assertEquals(Box(82, 94, 500, 166), MeikiPipeline.cropFor(Box(100, 100, 500, 160), 1920, 1080))
        // Tall boxes are vertical text: the padding turns with them.
        val tall = Box(100, 100, 160, 500)
        assertTrue(tall.vertical)
        assertEquals(Box(94, 82, 166, 500), MeikiPipeline.cropFor(tall, 1920, 1080))
        assertEquals(Box(0, 0, 1500, 66), MeikiPipeline.cropFor(Box(5, 2, 1500, 60), 1920, 1080))
    }

    @Test
    fun `recogniser sizes keep the aspect and fit the canvas`() {
        assertEquals(640 to 32, MeikiPipeline.recSize(1200, 60, vertical = false))
        assertEquals(960 to 16, MeikiPipeline.recSize(3000, 50, vertical = false))
        assertEquals(32 to 320, MeikiPipeline.recSize(60, 600, vertical = true))
    }

    @Test
    fun `characters found twice keep the surer one, in reading order`() {
        val codes = intArrayOf('た'.code, 'め'.code, '眺'.code, '挑'.code, 'x'.code)
        val boxes = floatArrayOf(
            64f, 0f, 96f, 32f,
            32f, 0f, 64f, 32f,
            0f, 0f, 32f, 32f,
            1f, 0f, 31f, 32f,   // the same cell as 眺, less sure
            100f, 0f, 120f, 32f, // below the score floor
        )
        val scores = floatArrayOf(0.9f, 0.9f, 0.8f, 0.4f, 0.05f)
        assertEquals("眺めた", MeikiPipeline.text(codes, boxes, scores, vertical = false))
        // Vertical text reads down the Y axis.
        val vBoxes = floatArrayOf(0f, 64f, 32f, 96f, 0f, 0f, 32f, 32f)
        assertEquals("上下", MeikiPipeline.text(intArrayOf('下'.code, '上'.code), vBoxes, floatArrayOf(0.9f, 0.9f), vertical = true))
    }

    @Test
    fun `code points beyond the BMP survive`() {
        assertEquals("𠮟", MeikiPipeline.text(intArrayOf(0x20B9F), floatArrayOf(0f, 0f, 32f, 32f), floatArrayOf(0.9f), vertical = false))
    }
}
