package app.genkan.capture

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible: asks Android for screen-capture consent and hands it to [CaptureService]. It
 * finishes a moment after the system dialog closes, because removing a window within ~400 ms
 * of another one going away or coming up crashes the Thor (T5).
 */
class CaptureConsentActivity : ComponentActivity() {

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureService.start(this, result.resultCode, data)
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, FINISH_DELAY_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }
    }

    private companion object {
        const val FINISH_DELAY_MS = 600L
    }
}
