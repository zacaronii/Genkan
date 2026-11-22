package app.genkan.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import kotlinx.coroutines.awaitCancellation
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
}
