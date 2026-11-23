package app.genkan.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The full box keeps the game's case in its slot until both faces have loaded. */
@OptIn(ExperimentalCoilApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36])
class FullBoxFallbackTest {
    // The host activity createComposeRule starts, registered here instead of through ui-test-manifest.
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

    /** A box whose scans "load" only when [loaded] says so; the others never arrive. */
    private fun box(loaded: Set<String>) = compose.setContent {
        CompositionLocalProvider(
            LocalInspectionMode provides true,
            LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { request ->
                val model = request.data.toString()
                // A scan that isn't "loaded" never arrives.
                if (model !in loaded) awaitCancellation()
                Bitmap.createBitmap(if (model == "spine") 20 else 200, 280, Bitmap.Config.ARGB_8888).asImage()
            },
        ) {
            FullBox(
                front = "front",
                spine = "spine",
                back = null,
                maxWidth = 300.dp,
                maxHeight = 300.dp,
                angle = 28f,
                fallback = { Text("the case") },
            )
        }
    }

    @Test
    fun `the case stands in while a face hasn't loaded`() {
        box(loaded = setOf("front"))
        compose.onNodeWithText("the case").assertExists()
    }

    @Test
    fun `once both faces have loaded the box replaces the case`() {
        box(loaded = setOf("front", "spine"))
        compose.onNodeWithText("the case").assertDoesNotExist()
    }

    /** A box at [angle] whose scans both load, with tagged overlays on its face and spine. */
    private fun overlays(angle: Float) = compose.setContent {
        CompositionLocalProvider(
            LocalInspectionMode provides true,
            LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { request ->
                Bitmap.createBitmap(if (request.data.toString() == "spine") 20 else 200, 280, Bitmap.Config.ARGB_8888).asImage()
            },
        ) {
            FullBox(
                front = "front", spine = "spine", back = "back", maxWidth = 300.dp, maxHeight = 300.dp, angle = angle,
                faceOverlay = { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().testTag("on-face")) },
                spineOverlay = { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().testTag("on-spine")) },
            )
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun overlaysOnFaces(angle: Float) {
        overlays(angle)
        assertEquals("face at $angle", bounds(FACE_TAG), bounds("on-face"))
        assertEquals("spine at $angle", bounds(SPINE_TAG), bounds("on-spine"))
    }

    @Test
    fun `the overlays sit on the drawn faces, not the box's layout`() = overlaysOnFaces(28f)

    @Test
    fun `seen from the back, the overlays still sit on the drawn faces`() = overlaysOnFaces(160f)

    @Test
    fun `the peek is placed against the drawn faces, not the box's layout`() {
        var behindWidth = 0f
        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { request ->
                    Bitmap.createBitmap(if (request.data.toString() == "spine") 20 else 200, 280, Bitmap.Config.ARGB_8888).asImage()
                },
            ) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                FullBox(
                    front = "front", spine = "spine", back = null, maxWidth = 300.dp, maxHeight = 300.dp, angle = 28f,
                    behind = { width, _ -> behindWidth = with(density) { width.toPx() } },
                    faceOverlay = { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().testTag("on-face")) },
                )
            }
        }
        compose.waitForIdle()
        val face = bounds(FACE_TAG)
        val spine = bounds(SPINE_TAG)
        val left = minOf(face.left, spine.left)
        assertEquals(maxOf(face.right, spine.right) - left, behindWidth, 1f)
        // The face is its scan's width (200 by 280), only turned: not squeezed into the box's layout.
        val turned = kotlin.math.cos(Math.toRadians(28.0)) * kotlin.math.cos(Math.toRadians(7.0))
        assertEquals(face.height * 200f / 280f * turned.toFloat(), face.width, 1f)
    }
}
