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
 * Drawn over the game in the player's process: a box round each line OCR read, where it sits,
 * and an [OverlayPanel] (a choice's answers) in the top right. A tap on a box or a panel row goes
 * to [onTap] (the line and the character tapped); any other touch passes through to the game.
 * Never takes focus, so the pad stays with the game.
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

    private var panel: OverlayPanel? = null
    private var panelGeometry: PanelGeometry? = null
    private val panelBox = RectF()
    private val panelFill = Paint().apply { color = Color.argb(200, 20, 20, 24) }
    private val panelHighlight = Paint().apply { color = Color.argb(150, 232, 176, 76) }
    private val panelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = PANEL_TEXT_SP * resources.displayMetrics.scaledDensity
    }
    private val panelPad = 10 * density

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    fun show(text: ScreenText) {
        screen = text
        invalidate()
    }

    /** Shows [next] in the top right, or no panel. */
    fun showPanel(next: OverlayPanel?) {
        panel = next
        panelGeometry = null
        invalidate()
    }

    private fun geometry(panel: OverlayPanel): PanelGeometry =
        panelGeometry ?: PanelGeometry.place(
            panel.items.size, width.toFloat(), height.toFloat(),
            rowHeight = panelText.textSize * 1.8f, margin = 16 * density,
        ).also { panelGeometry = it }

    /** The character of [row]'s text at [x], measured as drawn. */
    private fun offsetAt(panel: OverlayPanel, geometry: PanelGeometry, row: Int, x: Float): Int {
        val text = panel.items[row]
        val from = x - geometry.left - panelPad
        if (from <= 0f) return 0
        return panelText.breakText(text, true, from, null).coerceAtMost((text.length - 1).coerceAtLeast(0))
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 3 * density
        val corner = 6 * density
        for (line in screen.lines) {
            box.set(line.left * width - pad, line.top * height - pad, line.right * width + pad, line.bottom * height + pad)
            canvas.drawRoundRect(box, corner, corner, fill)
            canvas.drawRoundRect(box, corner, corner, stroke)
        }
        val panel = panel ?: return
        val g = geometry(panel)
        panelBox.set(g.left, g.top, g.left + g.width, g.bottom)
        canvas.drawRoundRect(panelBox, corner, corner, panelFill)
        panel.items.forEachIndexed { row, item ->
            val top = g.top + row * g.rowHeight
            if (row == panel.highlighted) {
                panelBox.set(g.left, top, g.left + g.width, top + g.rowHeight)
                canvas.drawRoundRect(panelBox, corner, corner, panelHighlight)
            }
            val baseline = top + (g.rowHeight - panelText.descent() - panelText.ascent()) / 2
            val shown = panelText.breakText(item, true, g.width - 2 * panelPad, null)
            canvas.drawText(item, 0, shown, g.left + panelPad, baseline, panelText)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width == 0 || height == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = panelHit(event.x, event.y) ?: screen.hit(event.x / width, event.y / height)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        panelGeometry = null
    }

    /** The panel row at ([x], [y]) and the character under it, or null off the panel. */
    private fun panelHit(x: Float, y: Float): ScreenText.Hit? {
        val panel = panel ?: return null
        val g = geometry(panel)
        val row = g.rowAt(x, y) ?: return null
        return ScreenText.Hit(panel.items[row], offsetAt(panel, g, row, x))
    }

    private companion object {
        /** The panel's text size, in sp: readable over the game at arm's length. */
        const val PANEL_TEXT_SP = 18f
    }
}
