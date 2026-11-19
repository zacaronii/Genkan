package app.genkan.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import androidx.core.graphics.createBitmap
import app.genkan.audio.GameAudioRecorder
import app.genkan.display.DisplayRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the top screen through MediaProjection. MediaProjection only mirrors the default
 * display, which is the game's, so the reader on the bottom screen never shows up in frames.
 *
 * The virtual display is made once per consent (T6: display queries during
 * createVirtualDisplay can deadlock the Thor's system_server), and consent doesn't survive
 * process death (T13), so a new process asks again.
 */
@Singleton
class ScreenCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val displays: DisplayRepository,
    private val audio: GameAudioRecorder,
) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var lastFrame: Bitmap? = null
    private val frameLock = Any()

    /** Starts mirroring with a granted consent; call from the foreground service. */
    fun start(resultCode: Int, data: Intent) {
        if (projection != null) return
        // Read before the projection exists: no display queries while the virtual display is made.
        val size = displays.sizeOf(Display.DEFAULT_DISPLAY) ?: return
        val density = context.resources.displayMetrics.densityDpi
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, data) ?: return
        val handlerThread = HandlerThread("ScreenCapture").apply { start() }
        val handler = Handler(handlerThread.looper)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stop()
        }, handler)
        val imageReader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mp.createVirtualDisplay(
            "genkan-capture", size.width, size.height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, handler,
        )
        projection = mp
        reader = imageReader
        thread = handlerThread
        _running.value = true
        Log.i(TAG, "Capturing the top screen at ${size.width}x${size.height}")
        // Game audio for sentence clips rides on the same consent, when recording is allowed.
        audio.start(mp)
    }

    /** Starts recording game audio after the permission arrives mid-capture. */
    fun startAudio() {
        projection?.let { audio.start(it) }
    }

    fun stop() {
        audio.stop()
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        thread?.quitSafely()
        thread = null
        synchronized(frameLock) { lastFrame = null }
        if (_running.value) Log.i(TAG, "Capture stopped")
        _running.value = false
    }

    /** The newest frame of the top screen (the last one again when nothing changed), or null. */
    fun frame(): Bitmap? = synchronized(frameLock) {
        val image = try {
            reader?.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        }
        if (image != null) {
            image.use {
                val plane = it.planes[0]
                // Rows can be padded: make the bitmap as wide as a row, then cut it to the image.
                val rowWidth = plane.rowStride / plane.pixelStride
                val padded = createBitmap(rowWidth, it.height)
                padded.copyPixelsFromBuffer(plane.buffer)
                lastFrame = if (rowWidth == it.width) padded else Bitmap.createBitmap(padded, 0, 0, it.width, it.height)
            }
        }
        lastFrame
    }

    private companion object {
        const val TAG = "ScreenCapture"
    }
}
