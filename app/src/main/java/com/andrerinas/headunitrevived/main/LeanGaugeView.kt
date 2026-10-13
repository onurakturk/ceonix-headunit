package com.andrerinas.headunitrevived.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * LEAN gauge for the Ride dashboard — the visual centrepiece. A 180° arc where the top (12 o'clock)
 * is upright, the right side a right lean and the left a left lean, capped at ±[MAX_ANGLE]°. A filled
 * arc grows from the top toward the current lean, tick marks flank the track, amber markers pin the
 * recorded max-left / max-right angles, and a dot rides the current position.
 *
 * The whole gauge is authored in terms of a single radius derived from the view **width**, and the
 * view measures its own height to a 2:1 semicircle box. Every stroke, tick and dot is a fixed ratio
 * of that radius, so it scales crisply to any screen size without distortion.
 *
 * The view is "dumb" — [RideFragment] feeds pre-smoothed values every frame. Long-press handling
 * (reset maxes + re-zero) lives in the fragment; the view is only marked long-clickable.
 */
class LeanGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Current lean in degrees, negative = left, positive = right. */
    var lean: Float = 0f
        set(value) { field = value.coerceIn(-MAX_ANGLE, MAX_ANGLE); invalidate() }

    /** Recorded maxima (absolute degrees, ≥0). 0 hides the marker. */
    var maxLeft: Int = 0
        set(value) { field = value; invalidate() }
    var maxRight: Int = 0
        set(value) { field = value; invalidate() }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    init {
        isLongClickable = true
    }

    /** Largest non-distorted radius that fits the slot (half the width, or the full height). */
    private fun radiusFor(w: Float, h: Float) = min(w / 2f, h) * 0.86f

    /**
     * Y of the semicircle's base line (its flat diameter). The arc needs enough top headroom for the
     * stroke's rounded cap and the dot so the peak is never clipped ([minBase]). When the slot is
     * taller than the gauge needs (a wide-aspect screen where the radius is width-limited), the base
     * line is pushed down to vertically centre the arc + readout group instead of floating at the top.
     * The clamp keeps short slots (e.g. the Mi9T) at the original top-anchored position — the base line
     * only ever moves *down*, so no device's peak is clipped by this.
     */
    private fun baseYFor(w: Float, h: Float, r: Float): Float {
        val topPad = r * 0.10f * 0.5f + 6f * (r / 95f) + dp(2f)
        val minBase = r + topPad                       // original top-anchored position (peak in view)
        val maxBase = max(minBase, h - r * 0.02f)      // base line stays inside the box
        val centered = h / 2f + r * 0.35f              // centre of the arc + readout composition
        return centered.coerceIn(minBase, maxBase)
    }

    /** Y of the gauge's base line, for callers aligning a label to the gauge floor. */
    fun baseLineY(): Float {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return h / 2f
        return baseYFor(w, h, radiusFor(w, h))
    }

    /** Map a lean angle to the arc's polar angle (deg): upright→90 (top), +right→0, −left→180. */
    private fun thetaOf(l: Float): Float = 90f - (l.coerceIn(-MAX_ANGLE, MAX_ANGLE) / MAX_ANGLE) * 90f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = radiusFor(w, h)
        if (r <= 0f) return
        val cx = w / 2f
        val scale = r / 95f       // handoff geometry was authored at r = 95
        val stroke = r * 0.10f
        val cy = baseYFor(w, h, r)

        val trackCol = ContextCompat.getColor(context, R.color.m3_surface_container_high)
        val amber = ContextCompat.getColor(context, R.color.m3_on_tertiary_cont)
        val outline = ContextCompat.getColor(context, R.color.m3_outline)

        // Track arc (180° → 0°, over the top).
        trackPaint.color = trackCol
        trackPaint.strokeWidth = stroke
        buildArc(180f, 0f, r, cx, cy)
        canvas.drawPath(path, trackPaint)

        // Ticks every 15°, major every 45°.
        var a = 0
        while (a <= 180) {
            val major = a % 45 == 0
            val inner = if (major) 26f else 20f
            tickPaint.color = outline
            tickPaint.strokeWidth = (if (major) 3f else 2f) * scale
            val t = a.toFloat()
            canvas.drawLine(
                cx + (r - 14f * scale) * cosf(t), cy - (r - 14f * scale) * sinf(t),
                cx + (r - inner * scale) * cosf(t), cy - (r - inner * scale) * sinf(t),
                tickPaint
            )
            a += 15
        }

        // Fill arc from top (90°) to current lean.
        val cur = thetaOf(lean)
        fillPaint.color = amber
        fillPaint.strokeWidth = stroke
        buildArc(90f, cur, r, cx, cy)
        canvas.drawPath(path, fillPaint)

        // Max-lean markers (amber, only when recorded).
        tickPaint.color = amber
        tickPaint.strokeWidth = 4f * scale
        if (maxRight > 0) drawMarker(canvas, thetaOf(maxRight.toFloat()), r, scale, cx, cy)
        if (maxLeft > 0) drawMarker(canvas, thetaOf(-maxLeft.toFloat()), r, scale, cx, cy)

        // Current position dot.
        dotPaint.color = amber
        canvas.drawCircle(cx + r * cosf(cur), cy - r * sinf(cur), 6f * scale, dotPaint)
    }

    private fun drawMarker(canvas: Canvas, theta: Float, r: Float, scale: Float, cx: Float, cy: Float) {
        canvas.drawLine(
            cx + (r - 8f * scale) * cosf(theta), cy - (r - 8f * scale) * sinf(theta),
            cx + (r - 30f * scale) * cosf(theta), cy - (r - 30f * scale) * sinf(theta),
            tickPaint
        )
    }

    /** Build a stroked arc into [path] by sampling, from thetaStart to thetaEnd (polar degrees). */
    private fun buildArc(thetaStart: Float, thetaEnd: Float, r: Float, cx: Float, cy: Float) {
        path.reset()
        val steps = max(2, (Math.abs(thetaStart - thetaEnd) / 2f).toInt())
        for (i in 0..steps) {
            val t = thetaStart + (thetaEnd - thetaStart) * (i.toFloat() / steps)
            val x = cx + r * cosf(t)
            val y = cy - r * sinf(t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    private fun cosf(deg: Float) = cos(Math.toRadians(deg.toDouble())).toFloat()
    private fun sinf(deg: Float) = sin(Math.toRadians(deg.toDouble())).toFloat()
    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        const val MAX_ANGLE = 45f
    }
}
