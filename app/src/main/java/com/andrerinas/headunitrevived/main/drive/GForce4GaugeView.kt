package com.andrerinas.headunitrevived.main.drive

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.R
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The Drive screen's G-FORCE page body, after the design handoff in
 * `design-assets/Pitch & Roll göstergesi tasarımı/design_handoff_gforce_card`: a "+" whose four
 * arms are made of 11 segments of 0.1 g each, every segment a little thicker than the one before,
 * lighting up from the centre outward. Bars that start from the same point overlapped at the
 * centre; separate, tapering segments cannot. Full scale is [FULL_G] (0.5 g since 1.3.13: daily
 * driving peaked at ~0.38 g and lit only 1-3 segments at 1.0 g). Rings mark half and full scale, segment 11 (past full scale)
 * is red, and a short gold bar on each arm pins that direction's maximum. The combined figure and
 * a 2x2 grid of maxima sit to the right, drawn smaller than the handoff so the cross leads.
 *
 * The view works in arm terms: [latArm] positive lights the right arm. The card decides what that
 * means (felt force: a left turn throws the occupants right, so it lights the right arm - the
 * user's choice over the handoff, which lights the turn's own side).
 *
 * Measured in the handoff's 576 x 432 card and scaled to the view, like PitchRollGaugeView.
 * Colours are theme tokens; text is cached per hundredth so a frame that changes nothing builds
 * no strings.
 */
class GForce4GaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** + accelerating (lights the lower arm), - braking (upper arm). */
    var longG = 0f
        set(v) { field = v; invalidate() }

    /** + lights the right arm, - the left. */
    var latArm = 0f
        set(v) { field = v; invalidate() }

    // Maxima per arm, positive magnitudes; 0 hides the marker.
    var maxBrake = 0f; set(v) { field = v; invalidate() }
    var maxAcc = 0f; set(v) { field = v; invalidate() }
    var maxLeftArm = 0f; set(v) { field = v; invalidate() }
    var maxRightArm = 0f; set(v) { field = v; invalidate() }

    private val armBrake = context.getString(R.string.drive_arm_brake)
    private val armAcc = context.getString(R.string.drive_arm_acc)
    private val armLeft = context.getString(R.string.drive_arm_left)
    private val armRight = context.getString(R.string.drive_arm_right)
    private val cellLabels = arrayOf(
        context.getString(R.string.ride_gforce_max_brake),
        context.getString(R.string.ride_gforce_max_acc),
        context.getString(R.string.drive_gforce_max_left),
        context.getString(R.string.drive_gforce_max_right)
    )
    private val unit = context.getString(R.string.ride_gforce_unit)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val armText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val cellValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val cellLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    // Text cache: the hundredths last formatted, and the strings built from them.
    private val centi = IntArray(5) { Int.MIN_VALUE }
    private val texts = arrayOfNulls<String>(5)
    private val cellValues = FloatArray(4)

    private var dashFor = -1f
    private var dashEffect: DashPathEffect? = null

    init {
        isLongClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val u = min(h / CARD_H, w / CARD_W)
        // The handoff's card is centred in whatever box we get.
        val ox = (w - CARD_W * u) / 2f
        val oy = (h - CARD_H * u) / 2f
        val cx = ox + CX * u
        val cy = oy + CY * u

        val gold = ContextCompat.getColor(context, R.color.m3_primary)
        val warn = ContextCompat.getColor(context, R.color.drive_warn)
        val track = ContextCompat.getColor(context, R.color.m3_surface_container_high)
        val onSurface = ContextCompat.getColor(context, R.color.m3_on_surface)
        val variant = ContextCompat.getColor(context, R.color.m3_on_surface_variant)

        // Rings at full scale (solid) and half scale (dashed).
        stroke.color = onSurface
        stroke.strokeWidth = 2f * u
        stroke.alpha = 18
        stroke.pathEffect = null
        canvas.drawCircle(cx, cy, 120f * u, stroke)
        stroke.alpha = 22
        stroke.pathEffect = dash(u)
        canvas.drawCircle(cx, cy, 60f * u, stroke)
        stroke.pathEffect = null

        // Tracks.
        fill.color = track
        val tr = 22f * u
        canvas.drawRoundRect(cx - 134f * u, cy - tr, cx + 134f * u, cy + tr, tr, tr, fill)
        canvas.drawRoundRect(cx - tr, cy - 134f * u, cx + tr, cy + 134f * u, tr, tr, fill)

        // Crosshair.
        stroke.alpha = 28
        canvas.drawLine(cx, cy - 120f * u, cx, cy + 120f * u, stroke)
        canvas.drawLine(cx - 120f * u, cy, cx + 120f * u, cy, stroke)

        // Segments, centre outward, on all four arms.
        val up = clampArm(-longG)        // braking lights the upper arm
        val down = clampArm(longG)
        val right = clampArm(latArm)
        val left = clampArm(-latArm)
        for (i in 1..SEGMENTS) {
            val c0 = (6f + (i - 1) * 10.5f) * u
            val c1 = c0 + 7f * u
            val th = (6f + (i - 1) * 2.4f) * u / 2f
            val r = 2f * u
            val litAt = (i - 0.5f) * FULL_G / 10f
            segColor(up >= litAt, i, gold, warn, onSurface)
            canvas.drawRoundRect(cx - th, cy - c1, cx + th, cy - c0, r, r, fill)
            segColor(down >= litAt, i, gold, warn, onSurface)
            canvas.drawRoundRect(cx - th, cy + c0, cx + th, cy + c1, r, r, fill)
            segColor(right >= litAt, i, gold, warn, onSurface)
            canvas.drawRoundRect(cx + c0, cy - th, cx + c1, cy + th, r, r, fill)
            segColor(left >= litAt, i, gold, warn, onSurface)
            canvas.drawRoundRect(cx - c1, cy - th, cx - c0, cy + th, r, r, fill)
        }

        // Maxima: a short gold bar across each arm.
        fill.color = gold
        fill.alpha = 255
        val mh = 14f * u; val mt = 1.5f * u; val mr = 2f * u
        if (maxBrake > 0f) { val d = clampArm(maxBrake) / FULL_G * SCALE * u; canvas.drawRoundRect(cx - mh, cy - d - mt, cx + mh, cy - d + mt, mr, mr, fill) }
        if (maxAcc > 0f) { val d = clampArm(maxAcc) / FULL_G * SCALE * u; canvas.drawRoundRect(cx - mh, cy + d - mt, cx + mh, cy + d + mt, mr, mr, fill) }
        if (maxRightArm > 0f) { val d = clampArm(maxRightArm) / FULL_G * SCALE * u; canvas.drawRoundRect(cx + d - mt, cy - mh, cx + d + mt, cy + mh, mr, mr, fill) }
        if (maxLeftArm > 0f) { val d = clampArm(maxLeftArm) / FULL_G * SCALE * u; canvas.drawRoundRect(cx - d - mt, cy - mh, cx - d + mt, cy + mh, mr, mr, fill) }

        // Arm labels.
        armText.color = variant
        armText.alpha = 190
        armText.textSize = 14f * u
        armText.letterSpacing = 2f / 14f
        fitWidth(armText, armBrake, 70f * u, 14f * u)
        canvas.drawText(armBrake, cx, cy - 156f * u + armText.textSize, armText)
        fitWidth(armText, armAcc, 70f * u, 14f * u)
        canvas.drawText(armAcc, cx, cy + 140f * u + armText.textSize, armText)
        armText.letterSpacing = 0f
        val sideBase = cy - (armText.ascent() + armText.descent()) / 2f
        fitWidth(armText, armLeft, 30f * u, 14f * u)
        canvas.drawText(armLeft, cx - 148f * u, sideBase, armText)
        fitWidth(armText, armRight, 30f * u, 14f * u)
        canvas.drawText(armRight, cx + 148f * u, sideBase, armText)

        // Combined figure.
        val total = sqrt(longG * longG + latArm * latArm)
        val colX = ox + COL_X * u
        bigText.textSize = 56f * u
        bigText.color = onSurface
        val totalText = text(0, total)
        val totalBase = oy + 182f * u
        canvas.drawText(totalText, colX, totalBase, bigText)
        val tw = bigText.measureText(totalText)
        bigText.textSize = 22f * u
        bigText.color = gold
        canvas.drawText(unit, colX + tw + 6f * u, totalBase, bigText)

        // Maxima grid: M. BRAKE, M. ACC / M. LEFT, M. RIGHT (left and right by arm).
        val cw = 84f * u; val ch = 58f * u; val gap = 10f * u
        val gy = oy + 206f * u
        val values = cellValues
        values[0] = maxBrake; values[1] = maxAcc; values[2] = maxLeftArm; values[3] = maxRightArm
        for (k in 0..3) {
            val x0 = colX + (k % 2) * (cw + gap)
            val y0 = gy + (k / 2) * (ch + gap)
            fill.color = track
            canvas.drawRoundRect(x0, y0, x0 + cw, y0 + ch, 12f * u, 12f * u, fill)
            val mid = x0 + cw / 2f
            cellValue.textSize = 20f * u
            cellValue.color = onSurface
            canvas.drawText(text(k + 1, values[k]), mid, y0 + ch / 2f + 2f * u, cellValue)
            cellLabel.color = variant
            fitWidth(cellLabel, cellLabels[k], cw - 10f * u, 11f * u)
            canvas.drawText(cellLabels[k], mid, y0 + ch / 2f + 2f * u + cellLabel.textSize + 4f * u, cellLabel)
        }
    }


    private fun segColor(lit: Boolean, i: Int, gold: Int, warn: Int, onSurface: Int) {
        if (!lit) {
            fill.color = onSurface
            fill.alpha = 12
        } else if (i > 10) {
            fill.color = warn
            fill.alpha = 255
        } else {
            fill.color = gold
            fill.alpha = ((0.35f + 0.065f * i).coerceAtMost(1f) * 255f).roundToInt()
        }
    }

    /** Sets [base] as the text size, shrunk so [s] fits in [maxW]. */
    private fun fitWidth(p: Paint, s: String, maxW: Float, base: Float) {
        p.textSize = base
        val tw = p.measureText(s)
        if (tw > maxW && tw > 0f) p.textSize = base * maxW / tw
    }

    private fun text(slot: Int, g: Float): String {
        val c = (g * 100f).roundToInt().coerceAtLeast(0)
        if (c != centi[slot] || texts[slot] == null) {
            centi[slot] = c
            texts[slot] = String.format(Locale.US, "%d.%02d", c / 100, c % 100)
        }
        return texts[slot]!!
    }

    private fun clampArm(g: Float) = g.coerceIn(0f, MAX_SHOWN)

    private fun dash(u: Float): DashPathEffect {
        if (u != dashFor || dashEffect == null) {
            dashFor = u
            dashEffect = DashPathEffect(floatArrayOf(6f * u, 5f * u), 0f)
        }
        return dashEffect!!
    }

    companion object {
        // The handoff's card, in its own pixels.
        private const val CARD_W = 576f
        private const val CARD_H = 432f
        private const val CX = 180f
        private const val CY = 238f
        private const val COL_X = 340f

        /** Full scale: the arm end. Maxima beyond it still show their true value in the M. boxes. */
        private const val FULL_G = 0.5f

        /** Full scale in handoff pixels. */
        private const val SCALE = 110f
        private const val SEGMENTS = 11
        private const val MAX_SHOWN = FULL_G * 1.15f
    }
}
