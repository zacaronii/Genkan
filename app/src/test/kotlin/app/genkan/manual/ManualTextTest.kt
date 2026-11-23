package app.genkan.manual

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.ocr.CharEnd
import app.genkan.ocr.OcrEngine
import app.genkan.ocr.OcrLine
import app.genkan.ui.ManualPages
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ManualTextTest {
    /** Reads one made-up line, 100 px from the left and 200 down, four characters 50 px each. */
    private class FakeEngine : OcrEngine {
        override val name = "fake"
        var reads = 0
        var widths = mutableListOf<Int>()
        var fail = false

        override suspend fun read(bitmap: Bitmap): List<OcrLine> {
            reads++
            widths += bitmap.width
            if (fail) throw IllegalStateException("no model")
            val ends = List(4) { CharEnd(it, 150 + it * 50) }
            return listOf(OcrLine("ことばを", top = 200, left = 100, height = 40, width = 200, ends = ends))
        }
    }

    private class Pages(private val renders: Boolean = true) : ManualPages {
        val bitmaps = mutableListOf<Bitmap>()
        override val pageSizes = List(3) { IntSize(600, 800) }
        override suspend fun render(index: Int, widthPx: Int): Bitmap? =
            if (!renders) null else Bitmap.createBitmap(widthPx, widthPx * 4 / 3, Bitmap.Config.ARGB_8888).also { bitmaps += it }

        override fun close() {}
    }

    @Test
    fun `a tap finds its character on the page, read at the unzoomed width`() = runTest {
        val engine = FakeEngine()
        val text = ManualText(engine)
        val pages = Pages()
        // The third character: from 200 to 250 px of a 600 px page, 220 down of 800.
        val hit = text.hit("manual.pdf", pages, 0, x = 225f / 600, y = 220f / 800, widthPx = 600)!!
        assertEquals("ことばを", hit.text)
        assertEquals(2, hit.offset)
        assertEquals(listOf(600), engine.widths)
        // Read once, its render given back.
        assertTrue(pages.bitmaps.single().isRecycled)
        assertNull(text.hit("manual.pdf", pages, 0, x = 0.5f, y = 0.9f, widthPx = 600))
    }

    @Test
    fun `a page is read once, per manual`() = runTest {
        val engine = FakeEngine()
        val text = ManualText(engine)
        val pages = Pages()
        assertTrue(!text.isRead("manual.pdf", 0))
        text.hit("manual.pdf", pages, 0, 0.3f, 0.27f, 600)
        assertTrue(text.isRead("manual.pdf", 0))
        text.hit("manual.pdf", pages, 0, 0.4f, 0.27f, 600)
        assertEquals(1, engine.reads)
        text.hit("manual.pdf", pages, 1, 0.4f, 0.27f, 600)
        text.hit("other.pdf", pages, 0, 0.4f, 0.27f, 600)
        assertEquals(3, engine.reads)
    }

    @Test
    fun `a page that won't render or read isn't kept, so the next tap tries again`() = runTest {
        val engine = FakeEngine()
        val text = ManualText(engine)
        assertNull(text.hit("manual.pdf", Pages(renders = false), 0, 0.3f, 0.27f, 600))
        assertTrue(!text.isRead("manual.pdf", 0))
        engine.fail = true
        val failed = runCatching { text.hit("manual.pdf", Pages(), 0, 0.3f, 0.27f, 600) }
        assertTrue(failed.isFailure)
        assertTrue(!text.isRead("manual.pdf", 0))
        engine.fail = false
        assertEquals(1, text.hit("manual.pdf", Pages(), 0, 175f / 600, 220f / 800, 600)!!.offset)
    }

    @Test
    fun `a manual replaced under the same name is read again, not taken for the old one`() = runTest {
        val engine = FakeEngine()
        val text = ManualText(engine)
        val pages = Pages()
        val manual = java.io.File.createTempFile("manual", ".pdf").apply { writeText("%PDF English"); setLastModified(1_000_000_000L) }
        text.hit(manual.path, pages, 0, 0.3f, 0.27f, 600)
        assertEquals(1, engine.reads)
        // A newer manual from the server takes its place, by the same name.
        manual.writeText("%PDF Japanese!"); manual.setLastModified(2_000_000_000L)
        assertTrue(!text.isRead(manual.path, 0))
        text.hit(manual.path, pages, 0, 0.3f, 0.27f, 600)
        assertEquals(2, engine.reads)
        manual.delete()
    }
}
