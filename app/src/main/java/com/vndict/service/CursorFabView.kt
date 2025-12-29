package com.vndict.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * A two-dot FAB view with a main circle button and a side dot cursor.
 * The side dot indicates where lookups will occur when hovering over OCR highlights.
 */
class CursorFabView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density

    // Main circle (56dp diameter)
    private val mainRadius = 28f * density
    // Cursor dot (10dp diameter - smaller for precision)
    private val cursorRadius = 5f * density
    // Distance from main circle edge to cursor center (cursor is close to main circle)
    private val cursorGap = 8f * density

    // Whether cursor dot is below (true) or above (false) the main circle
    var cursorOnBottom = true
        private set

    // Configurable colors
    private var fabColor: Int = Color.WHITE
    private var cursorDotColor: Int = 0xFFFF5722.toInt()
    private var accentColor: Int = 0xFF2196F3.toInt()

    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fabColor
        style = Paint.Style.FILL
        setShadowLayer(4f * density, 0f, 2f * density, Color.argb(80, 0, 0, 0))
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cursorDotColor
        style = Paint.Style.FILL
    }

    private val cursorBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    init {
        // Enable software layer for shadow rendering
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /**
     * Update the FAB colors.
     */
    fun updateColors(fabColor: Int, cursorDotColor: Int, accentColor: Int) {
        this.fabColor = fabColor
        this.cursorDotColor = cursorDotColor
        this.accentColor = accentColor
        mainPaint.color = fabColor
        cursorPaint.color = cursorDotColor
        invalidate()
    }

    /**
     * Get the cursor position in screen coordinates.
     * Cursor is at the edge of the view for maximum reach.
     */
    fun getCursorScreenX(): Float {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val cursorX = location[0] + width / 2f
        // Debug log occasionally
        if (System.currentTimeMillis() % 2000 < 30) {
            android.util.Log.d("VNDict", "FAB: location[0]=${location[0]}, width=$width, cursorX=$cursorX")
        }
        return cursorX
    }

    fun getCursorScreenY(): Float {
        val location = IntArray(2)
        getLocationOnScreen(location)
        // Cursor is at edge of main circle + gap
        val cursorDistFromCenter = mainRadius + cursorGap
        return if (cursorOnBottom) {
            location[1] + mainRadius + cursorDistFromCenter
        } else {
            location[1] + height - mainRadius - cursorDistFromCenter
        }
    }

    /**
     * Toggle cursor position between top and bottom of main circle.
     */
    fun toggleCursorSide() {
        cursorOnBottom = !cursorOnBottom
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Compact size: main circle + gap + cursor dot on one side
        val diameter = (mainRadius * 2).toInt()
        val cursorSpace = (cursorGap + cursorRadius * 2 + 4f * density).toInt()  // gap + dot + border padding
        setMeasuredDimension(diameter, diameter + cursorSpace)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f

        // Position main circle based on cursor position
        val mainCy = if (cursorOnBottom) {
            mainRadius  // Main at top when cursor at bottom
        } else {
            height - mainRadius  // Main at bottom when cursor at top
        }

        // Draw main circle (white background)
        canvas.drawCircle(cx, mainCy, mainRadius, mainPaint)

        // Draw kanji icon "辞" (dictionary)
        drawKanjiIcon(canvas, cx, mainCy)

        // Draw cursor dot - positioned at edge of main circle + gap
        val cursorCy = if (cursorOnBottom) {
            mainCy + mainRadius + cursorGap  // Below main circle
        } else {
            mainCy - mainRadius - cursorGap  // Above main circle
        }
        canvas.drawCircle(cx, cursorCy, cursorRadius, cursorPaint)
        canvas.drawCircle(cx, cursorCy, cursorRadius, cursorBorderPaint)
    }

    private fun drawKanjiIcon(canvas: Canvas, cx: Float, cy: Float) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = mainRadius * 1.0f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        // Center vertically using font metrics
        val metrics = textPaint.fontMetrics
        val textY = cy - (metrics.ascent + metrics.descent) / 2
        canvas.drawText("辞", cx, textY, textPaint)
    }
}
