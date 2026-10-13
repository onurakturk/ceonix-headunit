package com.andrerinas.headunitrevived.main.ride

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.R
import kotlin.math.abs

/**
 * Longitudinal g meter: a vertical bar pinned at 0.0 in the middle, growing up under braking and
 * down under acceleration (see [BRAKE_UP]), with a scale reading down its side and thin markers
 * pinning the recorded maxima.
 *
 * There are deliberately no ACC / BRAKE captions and no panel behind the bar. Those words are five
 * characters in English and six in German or Russian, so they overran the panel on anything but a
 * short one; writing per-locale shrink-to-fit logic would have been effort spent protecting
 * information the card already carries, since the MAX ACC / MAX BRAKE rows beside it name both
 * directions. Dropping them made the surrounding panel pointless too, and the bar took the room.
 *
 * Everything is derived from the view's own box, so it fits whatever the card gives it - the same
 * approach as LeanGaugeView, and the reason it survives the Mi A3's ~142dp of card interior.
 * Colours are resolved inside [onDraw] and never cached, so a theme change repaints correctly.
 *
 * Dumb by design: [GForceCard] feeds it pre-smoothed values every frame. The long-press that
 * resets the maxima is wired by the card; the view only marks itself long-clickable.
 */
class GForceGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Current longitudinal g: + accelerating, - braking. */
    var value: Float = 0f
        set(v) { field = v.coerceIn(-MAX_G, MAX_G); invalidate() }

    /**
     * Recorded maxima as positive magnitudes. 0 hides the marker.
     *
     * Deliberately NOT clamped to [MAX_G]: a rider can and does exceed full-scale deflection, and
     * the figure must survive for the MAX boxes to report it. Only the marker is pinned to the top
     * of the scale when it is drawn, since there is nowhere past that to draw it.
     */
    var maxAcc: Float = 0f
        set(v) { field = v.coerceAtLeast(0f); invalidate() }
    var maxBrake: Float = 0f
        set(v) { field = v.coerceAtLeast(0f); invalidate() }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }

    private val rect = RectF()

    init {
        isLongClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val gold = ContextCompat.getColor(context, R.color.m3_primary)
        val trackCol = ContextCompat.getColor(context, R.color.m3_surface_container_high)
        val muted = ContextCompat.getColor(context, R.color.m3_on_surface_variant)
        val amber = ContextCompat.getColor(context, R.color.m3_on_tertiary_cont)

        // The scale strip is sized to the text it has to hold; the bar takes everything else.
        scalePaint.textSize = (h * 0.075f).coerceIn(dp(8f), dp(12f))
        val scaleW = scalePaint.measureText(WIDEST_SCALE_LABEL) + dp(10f)
        // The bar is left-aligned and takes every pixel the scale does not need, which is what
        // dropping the captions and the panel bought us.
        // A small left inset so the max markers and the zero line, which overhang the bar on both
        // sides, are not clipped against the view edge.
        val barLeft = dp(4f)
        val barW = (w - scaleW - barLeft - dp(6f)).coerceAtLeast(dp(12f))
        val corner = dp(6f)

        // With no captions to leave room for, the bar runs the full height of the slot - inset just
        // enough that the end scale labels, which centre on the extremes, still fit inside the card.
        val endPad = scalePaint.textSize * 0.6f
        val top = endPad
        val bottom = h - endPad
        if (bottom <= top) return
        val mid = (top + bottom) / 2f
        val span = (bottom - top) / 2f

        // Which way the bar grows. The fill, the markers and the scale all read their sign from
        // here, so they can never drift apart.
        val dirSign = if (BRAKE_UP) -1f else 1f

        trackPaint.color = trackCol
        rect.set(barLeft, top, barLeft + barW, bottom)
        canvas.drawRoundRect(rect, corner, corner, trackPaint)

        // Fill from the zero line toward the reading.
        val travel = dirSign * (value / MAX_G) * span
        if (abs(travel) > 0.5f) {
            // Past the warning threshold the fill turns amber, so hard braking and a hard launch
            // announce themselves without the rider having to read the number.
            fillPaint.color = if (abs(value) > G_WARN) amber else gold
            if (travel > 0f) rect.set(barLeft, mid - travel, barLeft + barW, mid)
            else rect.set(barLeft, mid, barLeft + barW, mid - travel)
            canvas.drawRoundRect(rect, corner, corner, fillPaint)
        }

        // Recorded maxima, pinned across the bar. Same idea as the lean gauge's max markers, and
        // the same amber, so the two gauges speak one language.
        markerPaint.color = amber
        markerPaint.strokeWidth = dp(2.5f)
        val overhang = dp(3f)
        if (maxAcc > 0f) drawMarker(canvas, mid - dirSign * (maxAcc / MAX_G).coerceAtMost(1f) * span, barLeft, barW, overhang)
        if (maxBrake > 0f) drawMarker(canvas, mid + dirSign * (maxBrake / MAX_G).coerceAtMost(1f) * span, barLeft, barW, overhang)

        // Zero line, dashed, so the anchor point is unmistakable.
        zeroPaint.color = muted
        zeroPaint.strokeWidth = dp(1.5f)
        zeroPaint.pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
        canvas.drawLine(barLeft - dp(4f), mid, barLeft + barW + dp(4f), mid, zeroPaint)

        // Scale, in the strip beside the bar.
        scalePaint.color = muted
        val scaleX = barLeft + barW + dp(6f)
        for (i in SCALE_VALUES.indices) {
            val y = mid - dirSign * (SCALE_VALUES[i] / MAX_G) * span
            canvas.drawText(SCALE_LABELS[i], scaleX, y + scalePaint.textSize * 0.35f, scalePaint)
        }
    }

    private fun drawMarker(canvas: Canvas, y: Float, barLeft: Float, barW: Float, overhang: Float) {
        canvas.drawLine(barLeft - overhang, y, barLeft + barW + overhang, y, markerPaint)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        /**
         * Full-scale deflection. Dropped from 1.5 to 1.0 in 1.2.11: a ride log showed the real
         * peaks at +1.2 / -0.67 g but almost everything below 0.5, so the top third of the scale
         * was dead space and the bar barely moved. Values past this pin the bar at the end - the
         * MAX boxes still report the true figure.
         */
        const val MAX_G = 1.0f

        /** Above this the fill turns amber: the bike is near the limit of grip. */
        private const val G_WARN = 0.7f

        /**
         * Which way the bar grows. Braking up / acceleration down is the inertial convention - the
         * way a g-meter ball or a pendulum actually moves - and on a motorcycle it lines up with
         * weight transfer: bar up means the forks are diving, bar down means the rear is squatting.
         * So the gauge shows the bike stance the rider already feels, not just a number.
         *
         * Deliberately NOT GForceEstimator.G_SIGN. That one is the field escape hatch for a sensor
         * whose direction turns out inverted; keeping the two apart means a real sign fault stays
         * diagnosable instead of being tangled up with a presentation choice.
         */
        private const val BRAKE_UP = true

        private const val WIDEST_SCALE_LABEL = "1.0"

        private val SCALE_VALUES = floatArrayOf(1.0f, 0.5f, 0f, -0.5f, -1.0f)

        // Unsigned on purpose: a rider says "one g of braking", not "minus one g", and the headline
        // readout is unsigned too. Direction is carried by the MAX ACC / MAX BRAKE rows.
        private val SCALE_LABELS = arrayOf("1.0", "0.5", "0.0", "0.5", "1.0")
    }
}
