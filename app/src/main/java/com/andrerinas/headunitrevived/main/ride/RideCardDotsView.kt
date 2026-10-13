package com.andrerinas.headunitrevived.main.ride

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.R
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Page indicator for the Ride card slot: a gold pill for the current page, plain dots for the rest.
 *
 * It sits in the card's top-right corner rather than under the content. A ride card is only ~142dp
 * tall inside on a short screen (Mi A3) and the content stack already fills that, while the
 * top-right is empty on every page. Hence the convention this view establishes: the top-right
 * ~60x14dp of a ride page is reserved for the page indicator, and no card puts content there.
 *
 * Below two pages it hides itself entirely, so a single-card build looks exactly as it did before.
 * Colours are resolved inside onDraw and never cached, so a theme change repaints correctly - the
 * same discipline LeanGaugeView follows.
 */
class RideCardDotsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var count: Int = 0
        set(value) {
            if (field == value) return
            field = value
            visibility = if (value < 2) GONE else VISIBLE
            requestLayout()
            invalidate()
        }

    /** Settled page. Also snaps the pill, for callers that do not report scroll offsets. */
    var selected: Int = 0
        set(value) {
            field = value
            position = value.toFloat()
            invalidate()
        }

    /** Fractional page position, so the pill tracks the finger during a drag. */
    private var position = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pill = RectF()

    /** Feed from ViewPager2's onPageScrolled. */
    fun setScrollPosition(page: Int, offset: Float) {
        position = page + offset
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Every page gets an equal slot the width of the pill, so the overall width never changes
        // as the pill slides between them.
        val w = if (count < 2) 0f else count * dp(PILL_W) + (count - 1) * dp(GAP)
        setMeasuredDimension(
            resolveSize(ceil(w.toDouble()).toInt(), widthMeasureSpec),
            resolveSize(ceil(dp(THICKNESS).toDouble()).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val n = count
        if (n < 2) return

        val active = ContextCompat.getColor(context, R.color.m3_primary)
        val idle = ContextCompat.getColor(context, R.color.m3_outline)

        val slot = dp(PILL_W) + dp(GAP)
        val thickness = dp(THICKNESS)
        val cy = height / 2f
        val r = thickness / 2f

        // Idle dots, centred in their slots. The one under the pill is skipped.
        paint.color = Color.argb(
            (Color.alpha(idle) * IDLE_ALPHA).toInt(),
            Color.red(idle), Color.green(idle), Color.blue(idle)
        )
        val covered = position.roundToInt()
        for (i in 0 until n) {
            if (i == covered) continue
            canvas.drawCircle(i * slot + dp(PILL_W) / 2f, cy, r, paint)
        }

        // The pill, lerped to the fractional current position.
        val left = position.coerceIn(0f, (n - 1).toFloat()) * slot
        paint.color = active
        pill.set(left, cy - r, left + dp(PILL_W), cy + r)
        canvas.drawRoundRect(pill, r, r, paint)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        private const val PILL_W = 14f
        private const val GAP = 6f
        private const val THICKNESS = 5f
        private const val IDLE_ALPHA = 0.45f
    }
}
