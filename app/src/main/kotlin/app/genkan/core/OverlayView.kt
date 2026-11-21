package app.genkan.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import app.genkan.ocr.ScreenText

/**
 * Drawn over the game in the player's process: a box round each line OCR read, where it sits.
 * A tap on a box goes to [onTap] (the line and the character tapped); any other touch passes
 * through to the game. Never takes focus, so the pad stays with the game.
 */
class OverlayView(context: Context, private val onTap: (ScreenText.Hit) -> Unit) : View(context) {
    private var screen = ScreenText.EMPTY
    private var pressed: ScreenText.Hit? = null
    private val box = RectF()
    private val density = resources.displayMetrics.density
    private val fill = Paint().apply { color = Color.argb(40, 0, 0, 0) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.argb(170, 255, 255, 255)
    }

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    fun show(text: ScreenText) {
        screen = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 3 * density
        val corner = 6 * density
        for (line in screen.lines) {
            box.set(line.left * width - pad, line.top * height - pad, line.right * width + pad, line.bottom * height + pad)
            canvas.drawRoundRect(box, corner, corner, fill)
            canvas.drawRoundRect(box, corner, corner, stroke)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width == 0 || height == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = screen.hit(event.x / width, event.y / height)
                return pressed != null
            }
            MotionEvent.ACTION_UP -> {
                pressed?.let(onTap)
                pressed = null
            }
            MotionEvent.ACTION_CANCEL -> pressed = null
        }
        return true
    }
}
