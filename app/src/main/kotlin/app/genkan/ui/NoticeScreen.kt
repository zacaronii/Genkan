package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

/** Where the third-party notices are in the APK's assets (the build's noticeAsset task copies NOTICE there). */
const val NOTICE_ASSET = "licences/NOTICE.txt"

/** Settings › About › Licences: NOTICE as it is, the D-pad scrolling it. */
@Composable
fun NoticeScreen() {
    val context = LocalContext.current
    val text = remember { runCatching { context.assets.open(NOTICE_ASSET).bufferedReader().use { it.readText() } }.getOrDefault("") }
    NoticeContent(text)
}

@Composable
fun NoticeContent(text: String) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Licences", style = MaterialTheme.typography.displaySmall, color = colors.primary)
        HintRow(Hints.notice())
        // The page takes the pad itself: up and down scroll it, a screenful with the shoulders.
        Column(
            Modifier
                .weight(1f)
                .focusRequester(focus)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val by = when (event.key) {
                        Key.DirectionDown -> 120f
                        Key.DirectionUp -> -120f
                        Key.ButtonR1 -> 800f
                        Key.ButtonL1 -> -800f
                        else -> return@onKeyEvent false
                    }
                    scope.launch { scroll.animateScrollBy(by) }
                    true
                }
                .focusable()
                .verticalScroll(scroll),
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colors.onBackground)
        }
    }
}
