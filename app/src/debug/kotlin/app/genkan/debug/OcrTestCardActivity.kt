package app.genkan.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Debug builds only: a stand-in game for OCR tests, the way tools/hookbridge/test/fake_host.py
 * stands in for a hooked one. A VN-style dialogue box in the lower third types each line a
 * character at a time, holds it, then clears.
 *   adb shell am start --display 0 -n app.genkan/.debug.OcrTestCardActivity
 */
class OcrTestCardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var shown by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                var i = 0
                while (true) {
                    val line = LINES[i++ % LINES.size]
                    for (end in 1..line.length) {
                        shown = line.substring(0, end)
                        delay(TYPE_MS)
                    }
                    delay(HOLD_MS)
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF6A8CC7), Color(0xFFE3C9A8), Color(0xFF7A5C3E)))),
            ) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.34f)
                        .padding(16.dp)
                        .background(Color(0xCC101020), RoundedCornerShape(16.dp))
                        .border(3.dp, Color(0xFFE8D8B0), RoundedCornerShape(16.dp))
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                ) {
                    BasicText(shown, style = TextStyle(color = Color.White, fontSize = 26.sp, lineHeight = 36.sp))
                }
            }
        }
    }

    private companion object {
        const val TYPE_MS = 45L
        const val HOLD_MS = 4000L
        val LINES = listOf(
            "「おはよう。今日もいい天気だね」",
            "彼女はそう言って、窓の外を眺めた。",
            "桜の花びらが風に舞っている。",
            "「放課後、図書室で待ってるから」",
            "先生に叱られた後で、食べさせられなかったお弁当を開けた。",
        )
    }
}
