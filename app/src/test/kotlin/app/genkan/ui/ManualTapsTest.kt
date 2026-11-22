package app.genkan.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.manual.ManualText
import app.genkan.ocr.CharEnd
import app.genkan.ocr.OcrEngine
import app.genkan.ocr.OcrLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** What a tap on the manual says in its bar, and what it hands on to be looked up. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ManualTapsTest {
    /** One made-up line, 100 px from the left and 200 down, four characters 50 px each; [gate] holds the read until opened. */
    private class Engine : OcrEngine {
        override val name = "fake"
        var gate: CompletableDeferred<Unit>? = null
        var fail = false

        override suspend fun read(bitmap: Bitmap): List<OcrLine> {
            gate?.await()
            if (fail) throw IllegalStateException("no model")
            return listOf(OcrLine("ことばを", top = 200, left = 100, height = 40, width = 200, ends = List(4) { CharEnd(it, 150 + it * 50) }))
        }
    }

    private object Pages : ManualPages {
        override val pageSizes = List(2) { IntSize(600, 800) }
        override suspend fun render(index: Int, widthPx: Int): Bitmap = Bitmap.createBitmap(widthPx, widthPx * 4 / 3, Bitmap.Config.ARGB_8888)
        override fun close() {}
    }

    private val file = File("manual.pdf")

    private class Setup(scope: TestScope, val engine: Engine = Engine(), started: Boolean = true) {
        val looked = mutableListOf<Pair<String, Int>>()
        val taps = ManualTaps(ManualText(engine), scope.backgroundScope) { text, offset -> looked += text to offset; started }
    }

    @Test
    fun `a page's first tap says it's reading, then the word goes to be looked up and is boxed`() = runTest {
        val s = Setup(this)
        s.engine.gate = CompletableDeferred()
        // The third character: 200 to 250 of 600 px across, 220 of 800 down.
        s.taps.tap(file, Pages, 0, 225f / 600, 220f / 800, 600)
        runCurrent()
        assertEquals(ManualTaps.READING, s.taps.status.value)
        s.engine.gate!!.complete(Unit)
        runCurrent()
        assertNull(s.taps.status.value)
        assertEquals(listOf("ことばを" to 2), s.looked)
        assertEquals(0, s.taps.hit.value!!.page)
        assertEquals(2, s.taps.hit.value!!.hit.offset)
    }

    @Test
    fun `a page read before answers without saying so`() = runTest {
        val s = Setup(this)
        s.taps.tap(file, Pages, 0, 225f / 600, 220f / 800, 600)
        runCurrent()
        s.engine.gate = CompletableDeferred()
        s.taps.tap(file, Pages, 0, 125f / 600, 220f / 800, 600)
        runCurrent()
        assertNull(s.taps.status.value)
        assertEquals(listOf("ことばを" to 2, "ことばを" to 0), s.looked)
    }

    @Test
    fun `a miss says there's no text there, for a moment, and looks nothing up`() = runTest {
        val s = Setup(this)
        s.taps.tap(file, Pages, 0, 0.5f, 0.9f, 600)
        runCurrent()
        assertEquals(ManualTaps.NO_TEXT, s.taps.status.value)
        assertEquals(emptyList<Pair<String, Int>>(), s.looked)
        advanceTimeBy(ManualTaps.STATUS_MS + 1)
        assertNull(s.taps.status.value)
    }

    @Test
    fun `a page that can't be read says so`() = runTest {
        val s = Setup(this)
        s.engine.fail = true
        s.taps.tap(file, Pages, 0, 225f / 600, 220f / 800, 600)
        runCurrent()
        assertEquals(ManualTaps.COULD_NOT_READ, s.taps.status.value)
        assertEquals(emptyList<Pair<String, Int>>(), s.looked)
    }

    @Test
    fun `no box when the lookup didn't start`() = runTest {
        val s = Setup(this, started = false)
        s.taps.tap(file, Pages, 0, 225f / 600, 220f / 800, 600)
        runCurrent()
        assertEquals(1, s.looked.size)
        assertNull(s.taps.hit.value)
    }

    @Test
    fun `a message doesn't wipe a newer one when its moment is up`() = runTest {
        val s = Setup(this)
        s.taps.say("Cards from the manual aren't made yet")
        runCurrent()
        s.engine.gate = CompletableDeferred()
        s.taps.tap(file, Pages, 1, 225f / 600, 220f / 800, 600)
        runCurrent()
        advanceTimeBy(ManualTaps.STATUS_MS + 1)
        assertEquals(ManualTaps.READING, s.taps.status.value)
    }
}
