package com.trackeye.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Minimalist HUD: gaze dot + dwell progress ring + gesture pulse. */
class GazeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var gaze: GazePoint? = null
        set(value) {
            field = value
            invalidate()
        }
    var dwellProgress: Float = 0f
        set(value) {
            field = value
            invalidate()
        }
    var pulse: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var trackingActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00D4A6")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6C63FF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD166")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText(if (trackingActive) "● TRACKING" else "○ STOP", 16f, 36f, statusPaint)
        val g = gaze ?: return
        val cx = g.x * width
        val cy = g.y * height
        if (pulse) canvas.drawCircle(cx, cy, 46f, pulsePaint)
        if (dwellProgress > 0f) {
            canvas.drawArc(cx - 34f, cy - 34f, cx + 34f, cy + 34f, -90f, 360f * dwellProgress, false, ringPaint)
        }
        canvas.drawCircle(cx, cy, 13f, dotPaint)
    }
}
