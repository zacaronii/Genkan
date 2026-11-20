package app.genkan.core

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Choreographer
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.swordfish.libretrodroid.GenkanTap
import java.nio.ByteBuffer

/**
 * A DS's lower (touch) screen on the bottom display, over the reader. Hidden, it shows the reader
 * beneath; shown again, it takes touches for the game. The frames come from the core's stacked
 * frame, whose bottom half this draws at each vsync.
 */
class LowerScreen(context: Context, display: Display) : Presentation(context, display) {

    private val screen = ScreenView(context)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(screen)
        // The game keeps the keys: the top display's window must stay focused.
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setCancelable(false)
    }

    override fun onStart() {
        super.onStart()
        screen.running = true
    }

    override fun onStop() {
        screen.running = false
        GenkanTap.touchLowerScreen(-1f, -1f)
        super.onStop()
    }

    /** Shows the reader instead, or the game's screen again. */
    fun toggle() {
        if (isShowing) hide() else show()
    }

    private class ScreenView(context: Context) : View(context), Choreographer.FrameCallback {
        private var bitmap: Bitmap? = null
        private var pixels: ByteBuffer? = null
        private val target = Rect()
        private val paint = Paint().apply { isFilterBitmap = false }

        var running = false
            set(value) {
                field = value
                Choreographer.getInstance().removeFrameCallback(this)
                if (value) Choreographer.getInstance().postFrameCallback(this)
            }

        init {
            setBackgroundColor(Color.BLACK)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            Choreographer.getInstance().postFrameCallback(this)
            val (width, height) = GenkanTap.frameSize() ?: return
            val half = height / 2
            val bmp = bitmap?.takeIf { it.width == width && it.height == half }
                ?: Bitmap.createBitmap(width, half, Bitmap.Config.ARGB_8888).also {
                    bitmap = it
                    pixels = ByteBuffer.allocateDirect(width * half * 4)
                }
            val buffer = pixels ?: return
            buffer.rewind()
            if (!GenkanTap.copyFrame(buffer, half, half)) return
            buffer.rewind()
            bmp.copyPixelsFromBuffer(buffer)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val bmp = bitmap ?: return
            // As large as fits, its shape kept, centred.
            val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            val w = (bmp.width * scale).toInt()
            val h = (bmp.height * scale).toInt()
            target.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            canvas.drawBitmap(bmp, null, target, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (target.isEmpty) return true
                    val x = ((event.x - target.left) / target.width()).coerceIn(0f, 1f)
                    val y = ((event.y - target.top) / target.height()).coerceIn(0f, 1f)
                    GenkanTap.touchLowerScreen(x, y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> GenkanTap.touchLowerScreen(-1f, -1f)
            }
            return true
        }
    }

    companion object {
        /** The other screen, when the device has one Presentations may use. */
        fun displayFor(context: Context): Display? =
            context.getSystemService(DisplayManager::class.java)
                .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }
}
