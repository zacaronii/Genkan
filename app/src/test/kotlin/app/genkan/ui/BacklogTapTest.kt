package app.genkan.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.genkan.reader.ReaderLineEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A tap on a backlog character looks it up; a tap on no character closes the popup. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36], qualifiers = "w538dp-h468dp-land-369dpi")
class BacklogTapTest {
    @get:Rule(order = 0)
    val activity = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
            org.robolectric.Shadows.shadowOf(context.packageManager)
                .addActivityIfNotPresent(android.content.ComponentName(context, androidx.activity.ComponentActivity::class.java))
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val lines = listOf(
        ReaderLineEntity(id = 1, gameKey = "g", text = "みず。", at = 0),
        ReaderLineEntity(id = 2, gameKey = "g", text = "やま。", at = 1),
    )
    private val taps = mutableListOf<String>()

    private fun show() {
        compose.setContent {
            Backlog(
                lines, lookup = Lookup(1, 0, null, "みず。"), panelHeight = 0.dp, marks = emptyMap(),
                onTap = { line, offset -> taps += "word ${line.id}:$offset" },
                onBackground = { taps += "background" },
            )
        }
        compose.waitForIdle()
    }

    private fun bounds(text: String): Rect = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    private fun tap(at: Offset) {
        compose.onRoot().performTouchInput { click(at) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test
    fun `a tap on a character looks it up`() {
        show()
        val line = bounds("みず。")
        tap(Offset(line.left + 4f, line.center.y))
        assertEquals(listOf("word 1:0"), taps)
    }

    @Test
    fun `a tap past the end of a short line closes the popup`() {
        show()
        val line = bounds("みず。")
        tap(Offset(line.right - 4f, line.center.y))
        assertEquals(listOf("background"), taps)
    }

    @Test
    fun `a tap between lines closes the popup`() {
        show()
        val first = bounds("みず。")
        val second = bounds("やま。")
        tap(Offset(first.left + 4f, (first.bottom + second.top) / 2))
        assertEquals(listOf("background"), taps)
    }

    @Test
    fun `a tap below the last line closes the popup`() {
        show()
        val last = bounds("やま。")
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        tap(Offset(last.left + 4f, (last.bottom + root.bottom) / 2))
        assertEquals(listOf("background"), taps)
    }

    @Test
    fun `a tap on the bar while a popup is up only closes it`() {
        var closed = 0
        var pressed = 0
        compose.setContent {
            Column(Modifier.fillMaxSize()) {
                TapCloses(active = true, onClose = { closed++ }) {
                    Box(Modifier.fillMaxWidth().height(60.dp)) { Chip("OCR") { pressed++ } }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("OCR").performClick()
        val bar = compose.onNodeWithTag(BAR_CLOSES_POPUP).fetchSemanticsNode().boundsInRoot
        tap(Offset(bar.right - 10f, bar.center.y))
        assertEquals(2, closed)
        assertEquals(0, pressed)
    }

    @Test
    fun `without a popup the bar's controls work`() {
        var pressed = 0
        compose.setContent { TapCloses(active = false, onClose = {}) { Chip("OCR") { pressed++ } } }
        compose.onNodeWithText("OCR").performClick()
        assertEquals(1, pressed)
    }
}
