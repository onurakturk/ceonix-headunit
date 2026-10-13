package com.andrerinas.headunitrevived.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.R
import kotlin.math.min

/**
 * Small vertical Material-style battery indicator. The fill height tracks the charge level
 * (so the icon visibly changes by level); below 20% the whole glyph switches to the error
 * colour. Theme-aware via the m3_* colour resources. Set the level with [setLevel].
 */
class BatteryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Design viewBox: 24 wide x 40 tall.
    private val vbW = 24f
    private val vbH = 40f

    private var level = 100

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val body = RectF()
    private val cap = RectF()
    private val fill = RectF()

    fun setLevel(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        if (clamped != level) {
            level = clamped
            invalidate()
        }
    }

    /** True once we consider the battery low (drives the error colour). */
    fun isLow(): Boolean = level <= 20

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val color = ContextCompat.getColor(
            context,
            if (isLow()) R.color.m3_error else R.color.m3_primary
        )
        outlinePaint.color = color
        fillPaint.color = color
        capPaint.color = color

        val scale = min(width / vbW, height / vbH)
        val dx = (width - vbW * scale) / 2f
        val dy = (height - vbH * scale) / 2f

        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)

        // Terminal cap
        cap.set(8f, 0f, 16f, 4f)
        canvas.drawRoundRect(cap, 1.5f, 1.5f, capPaint)

        // Body outline
        body.set(3f, 5f, 21f, 39f)
        canvas.drawRoundRect(body, 4f, 4f, outlinePaint)

        // Fill grows from the bottom
        val innerTop = 8f
        val innerBottom = 36f
        val innerH = innerBottom - innerTop
        val fillH = innerH * (level / 100f)
        fill.set(6f, innerBottom - fillH, 18f, innerBottom)
        canvas.drawRoundRect(fill, 2f, 2f, fillPaint)

        canvas.restore()
    }
}
