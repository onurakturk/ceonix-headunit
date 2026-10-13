package com.andrerinas.headunitrevived.main.ride

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.sensors.AltitudeTracker

/**
 * Small altitude trace beside the readout: a gold line with a soft fill under it, showing the
 * climbing and descending of roughly the last 40 km. The tracker samples by distance, so this is a
 * route profile rather than a time series - a long stop leaves the trace where it was.
 *
 * The vertical axis auto-scales to whatever the window holds, so a gentle rise still has a visible
 * shape instead of flattening against a fixed range. Below two samples it draws nothing, which is
 * what the card looks like before the first fixes arrive.
 *
 * House style, same as LeanGaugeView and GForceGaugeView: geometry from the view's own box,
 * colours resolved inside [onDraw] so a theme change repaints correctly, no allocation per frame -
 * the sample buffer and both paths are reused.
 */
class AltitudeSparkView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val samples = FloatArray(CAPACITY)
    private var count = 0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val linePath = Path()
    private val fillPath = Path()

    /** Pull the latest trace. Cheap, but the card only calls it when the history actually grew. */
    fun refresh() {
        count = AltitudeTracker.snapshot(samples)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // A faint floor is always drawn, so the card reads as "chart here, nothing yet" rather than
        // as dead space over the first kilometre. Nothing is ever invented: the moment real samples
        // arrive the gold trace simply lands on top of it.
        val outline = ContextCompat.getColor(context, R.color.m3_outline)
        basePaint.color = Color.argb(
            (Color.alpha(outline) * BASE_ALPHA).toInt(),
            Color.red(outline), Color.green(outline), Color.blue(outline)
        )
        basePaint.strokeWidth = dp(1.5f)
        canvas.drawLine(0f, h / 2f, w, h / 2f, basePaint)

        val n = count
        if (n < 2) return

        var lo = samples[0]
        var hi = samples[0]
        for (i in 1 until n) {
            if (samples[i] < lo) lo = samples[i]
            if (samples[i] > hi) hi = samples[i]
        }
        // A flat stretch would divide by zero and also deserves to sit mid-height rather than
        // being stretched into noise, so give it a floor of a few metres.
        var range = hi - lo
        if (range < MIN_RANGE_M) {
            val mid = (hi + lo) / 2f
            lo = mid - MIN_RANGE_M / 2f
            range = MIN_RANGE_M
        }

        val stroke = dp(2f)
        val top = stroke / 2f
        val usable = h - stroke
        val dx = w / (n - 1).toFloat()

        linePath.reset()
        for (i in 0 until n) {
            val x = i * dx
            val y = top + (1f - (samples[i] - lo) / range) * usable
            if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }

        // Fill: the same trace closed down to the baseline.
        fillPath.set(linePath)
        fillPath.lineTo(w, h)
        fillPath.lineTo(0f, h)
        fillPath.close()

        val gold = ContextCompat.getColor(context, R.color.m3_primary)
        fillPaint.color = Color.argb(
            (Color.alpha(gold) * FILL_ALPHA).toInt(), Color.red(gold), Color.green(gold), Color.blue(gold)
        )
        canvas.drawPath(fillPath, fillPaint)

        linePaint.color = gold
        linePaint.strokeWidth = stroke
        canvas.drawPath(linePath, linePaint)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        /** Matches AltitudeTracker's ring so a full window is never truncated. Change both. */
        private const val CAPACITY = 40
        private const val MIN_RANGE_M = 12f
        private const val FILL_ALPHA = 0.22f

        /** Faint enough that the real trace never competes with it. */
        private const val BASE_ALPHA = 0.30f
    }
}
