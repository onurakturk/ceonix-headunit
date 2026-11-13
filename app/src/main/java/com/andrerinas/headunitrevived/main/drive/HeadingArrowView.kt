package com.andrerinas.headunitrevived.main.drive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.R
import kotlin.math.min

/**
 * The Drive screen's heading arrow, after the design handoff in
 * `design-assets/Pitch & Roll göstergesi tasarımı/design_handoff_heading_card`: no dial, just one
 * navigation arrowhead, two-tone gold, turned to the direction of travel (0 = up/north, clockwise).
 *
 * It is laid over the whole right card and placed in the handoff's own card coordinates (a
 * 460 x 432 card, rotation centre at 322, 316), so it stays in proportion with the card. Drawn at
 * [ARROW_SCALE] of the handoff's size and without its glow - both the user's call after seeing it
 * on the device: the full size dominated the card and the halo muddied the flat design.
 *
 * Dumb: [heading] arrives already eased the shorter way round, and a rotation of 359 is drawn the
 * same as -1, so crossing north never spins it. [hasHeading] false hides it until the first course.
 */
class HeadingArrowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var heading = 0f
        set(v) { field = v; invalidate() }

    var hasHeading = false
        set(v) { field = v; invalidate() }

    private val lightHalf = Path()
    private val darkHalf = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var builtFor = -1f

    override fun onDraw(canvas: Canvas) {
        if (!hasHeading) return
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val u = min(h / CARD_H, w / CARD_W)
        val cx = (w - CARD_W * u) / 2f + CX * u
        val cy = (h - CARD_H * u) / 2f + CY * u
        if (u != builtFor) build(u)

        val gold = ContextCompat.getColor(context, R.color.m3_primary)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(heading)
        fill.color = gold
        canvas.drawPath(lightHalf, fill)
        fill.color = darker(gold)
        canvas.drawPath(darkHalf, fill)
        canvas.restore()
    }

    /**
     * The handoff's polygon in its 108 x 142 box, placed at (21, 4) inside a 150 x 150 box that
     * turns about its centre: tip (54, 0), right barb (108, 142), notch (54, 108), left barb
     * (0, 142). Scaled by [ARROW_SCALE] about that centre and split down the centre line into a
     * light and a dark half for the two-tone look.
     */
    private fun build(u: Float) {
        builtFor = u
        val k = u * ARROW_SCALE
        val ox = 21f - 75f; val oy = 4f - 75f
        fun x(v: Float) = (ox + v) * k
        fun y(v: Float) = (oy + v) * k
        lightHalf.reset()
        lightHalf.moveTo(x(54f), y(0f)); lightHalf.lineTo(x(54f), y(108f)); lightHalf.lineTo(x(0f), y(142f)); lightHalf.close()
        darkHalf.reset()
        darkHalf.moveTo(x(54f), y(0f)); darkHalf.lineTo(x(108f), y(142f)); darkHalf.lineTo(x(54f), y(108f)); darkHalf.close()
    }

    /** About the handoff's #D9AC0C against #F5C518: the same gold, 12% darker. */
    private fun darker(c: Int) = Color.rgb(
        (Color.red(c) * 0.88f).toInt(), (Color.green(c) * 0.88f).toInt(), (Color.blue(c) * 0.88f).toInt()
    )

    companion object {
        private const val CARD_W = 460f
        private const val CARD_H = 432f
        private const val CX = 322f
        private const val CY = 316f

        /** Share of the handoff's arrow size (user: 20-30% smaller, in proportion with the card). */
        private const val ARROW_SCALE = 0.75f
    }
}
