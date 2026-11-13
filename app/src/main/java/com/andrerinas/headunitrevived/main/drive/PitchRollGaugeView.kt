package com.andrerinas.headunitrevived.main.drive

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Drive screen's PITCH & ROLL inclinometer, after the design handoff in
 * `design-assets/Pitch & Roll göstergesi tasarımı/design_handoff_pitch_roll_card`: two segmented
 * arcs (pitch on the left, roll on the right) of 9 blocks of 5 deg each way that thicken outward
 * and fill from 0 - the G-FORCE page's language - with red past the warning threshold and a small
 * gold marker outside the arc at each direction's maximum; a side-view car and a rear-view car
 * tilting against a dashed horizon; and the readouts in the bottom corners, each label carrying
 * that side's maxima.
 *
 * Everything is measured in the handoff's units - a 576 x 432 card with the gauge centred at
 * (288, 206) - and scaled by the view height, so the same drawing fits every screen. Angles follow
 * the handoff too: clockwise from 12 o'clock.
 *
 * Two deliberate departures, both the user's call: the cars are minimal silhouettes rather than the
 * handoff's stacked boxes (the side view only has to make nose and tail obvious), and the title is
 * a TextView in the top-left like every other card, since the top-right holds the page dots.
 * Colours come from the theme tokens, so the light theme keeps working.
 *
 * Dumb: the card feeds eased values every frame. Paths are rebuilt only on a size change.
 */
class PitchRollGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Degrees, + nose up. */
    var pitch = 0f
        set(v) { field = v; invalidate() }

    /** Degrees, + right side down. */
    var roll = 0f
        set(v) { field = v; invalidate() }

    /** Per side: false until that side has a reading (pitch: one grade window travelled; roll: the
     *  mount learned). A side that is not ready is muted, level, unlit, with no number. */
    var pitchReady = false
        set(v) { field = v; invalidate() }

    var rollReady = false
        set(v) { field = v; invalidate() }

    // Highest tilt reached per direction, positive degrees; 0 hides the marker.
    var maxPitchUp = 0f; set(v) { field = v; invalidate() }
    var maxPitchDown = 0f; set(v) { field = v; invalidate() }
    var maxRollLeft = 0f; set(v) { field = v; invalidate() }
    var maxRollRight = 0f; set(v) { field = v; invalidate() }

    private val labelPitch = context.getString(R.string.drive_pitch)
    private val labelRoll = context.getString(R.string.drive_roll)
    private val maxLabel = context.getString(R.string.drive_max)
    private val noSignal = context.getString(R.string.ride_no_signal)

    // The MAX rows, rebuilt only when a rounded maximum changes.
    private var pitchMax: String? = null
    private var rollMax: String? = null
    private var cachedPitchUp = Int.MIN_VALUE; private var cachedPitchDown = Int.MIN_VALUE
    private var cachedRollLeft = Int.MIN_VALUE; private var cachedRollRight = Int.MIN_VALUE

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tyrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val dirPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    private val block = RectF()

    // Silhouettes in handoff units around their own centre; scaled when drawn.
    private val sideBody = Path()
    private val sideWindow = Path()
    private val rearBody = Path()
    private val rearWindow = Path()
    private var builtFor = -1f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val u = min(h / CARD_H, w / CARD_W)
        val cx = w / 2f
        val cy = CENTER_Y * u
        if (u != builtFor) build(u)

        val gold = ContextCompat.getColor(context, R.color.m3_primary)
        val warn = ContextCompat.getColor(context, R.color.drive_warn)
        val outline = ContextCompat.getColor(context, R.color.m3_outline)
        val onSurface = ContextCompat.getColor(context, R.color.m3_on_surface)
        val variant = ContextCompat.getColor(context, R.color.m3_on_surface_variant)
        val cardBg = ContextCompat.getColor(context, R.color.m3_surface_container)

        val pc = pitch.coerceIn(-CLAMP, CLAMP)
        val rc = roll.coerceIn(-CLAMP, CLAMP)
        val pitchWarn = pitchReady && abs(pitch) >= PITCH_WARN
        val rollWarn = rollReady && abs(roll) >= ROLL_WARN

        // Segmented arcs, the G-FORCE page's language: 9 blocks of 5 deg each way, thickening
        // outward, filling from 0. Pitch is the left arc (270, nose up turns clockwise = up), roll
        // the right (90, right side down turns clockwise = down).
        val up = if (pitchReady) pc else 0f
        val rl = if (rollReady) rc else 0f
        for (i in 1..SEGMENTS) {
            val centre = 5f * i - 2.5f
            segment(canvas, cx, cy, 270f + centre, i, up >= centre, centre >= PITCH_WARN, gold, warn, onSurface, u)
            segment(canvas, cx, cy, 270f - centre, i, -up >= centre, centre >= PITCH_WARN, gold, warn, onSurface, u)
            segment(canvas, cx, cy, 90f + centre, i, rl >= centre, centre >= ROLL_WARN, gold, warn, onSurface, u)
            segment(canvas, cx, cy, 90f - centre, i, -rl >= centre, centre >= ROLL_WARN, gold, warn, onSurface, u)
        }

        // Zero markers at 9 and 3 o'clock.
        markPaint.color = onSurface
        markPaint.alpha = 230
        radial(canvas, cx, cy, 270f, 4f, 182f, 26f, u)
        radial(canvas, cx, cy, 90f, 4f, 182f, 26f, u)

        // Maxima, just outside the arc: gold, or red once past the warning threshold.
        maxMark(canvas, cx, cy, 270f, maxPitchUp, 1f, PITCH_WARN, gold, warn, u)
        maxMark(canvas, cx, cy, 270f, maxPitchDown, -1f, PITCH_WARN, gold, warn, u)
        maxMark(canvas, cx, cy, 90f, maxRollRight, 1f, ROLL_WARN, gold, warn, u)
        maxMark(canvas, cx, cy, 90f, maxRollLeft, -1f, ROLL_WARN, gold, warn, u)

        // Flat ground, dashed, and the divider between the two halves.
        linePaint.color = outline
        linePaint.alpha = 90
        linePaint.strokeWidth = 2f * u
        linePaint.pathEffect = dash(u)
        canvas.drawLine(cx - 150f * u, cy, cx + 150f * u, cy, linePaint)
        linePaint.pathEffect = null
        linePaint.alpha = 60
        canvas.drawLine(cx, cy - 110f * u, cx, cy + 110f * u, linePaint)

        // Cars, each muted until its own side has a reading.
        cutPaint.color = cardBg
        ringPaint.strokeWidth = 5f * u
        carPaints(pitchReady, onSurface, variant, gold, outline)
        drawSideCar(canvas, cx - 78f * u, cy, if (pitchReady) pc else 0f, u)
        carPaints(rollReady, onSurface, variant, gold, outline)
        drawRearCar(canvas, cx + 78f * u, cy, if (rollReady) rc else 0f, u)

        // Readouts: the value, then the label with this side's maxima beside it.
        val p = pitch.roundToInt()
        val rr = roll.roundToInt()
        val pitchText = if (!pitchReady) noSignal else (if (p > 0) "+" else "") + p + "°"
        val rollText = if (!rollReady) noSignal else abs(rr).toString() + "°"
        val side = 30f * u
        val maxRow = w * 0.47f - side
        val muted = withAlpha(variant, 170)
        readout(canvas, side, h - 20f * u, false, pitchText, labelPitch, pitchMaxText(),
            if (pitchWarn) warn else onSurface, gold, muted, maxRow, u)
        readout(canvas, w - side, h - 20f * u, true, rollText, labelRoll, rollMaxText(),
            if (rollWarn) warn else onSurface, gold, muted, maxRow, u)
    }

    /**
     * One radial block at [deg] (clockwise from 12): 11 wide along the arc, outer edge at 180,
     * 8 + (i-1)*2 thick. Lit blocks go from soft to full gold outward, red past the threshold;
     * unlit ones stay faintly visible as the scale.
     */
    private fun segment(
        canvas: Canvas, cx: Float, cy: Float, deg: Float, i: Int, lit: Boolean, beyondWarn: Boolean,
        gold: Int, warn: Int, onSurface: Int, u: Float
    ) {
        when {
            !lit -> { markPaint.color = onSurface; markPaint.alpha = 18 }
            beyondWarn -> { markPaint.color = warn; markPaint.alpha = 255 }
            else -> {
                markPaint.color = gold
                markPaint.alpha = ((0.4f + 0.067f * i).coerceAtMost(1f) * 255f).roundToInt()
            }
        }
        radial(canvas, cx, cy, deg, 11f, 180f, 8f + (i - 1) * 2f, u)
    }

    /** The maximum for one direction ([sign] picks which side of [base]); hidden below half a degree. */
    private fun maxMark(
        canvas: Canvas, cx: Float, cy: Float, base: Float, max: Float, sign: Float, warnAt: Float,
        gold: Int, warn: Int, u: Float
    ) {
        if (max < 0.5f) return
        markPaint.color = if (max >= warnAt) warn else gold
        markPaint.alpha = 255
        radial(canvas, cx, cy, base + sign * max.coerceAtMost(CLAMP), 4f, 196f, 12f, u)
    }

    /** A rounded block across the radius at [deg]: [width] wide, from [outer] inward by [length]. */
    private fun radial(canvas: Canvas, cx: Float, cy: Float, deg: Float, width: Float, outer: Float, length: Float, u: Float) {
        canvas.save()
        canvas.rotate(deg, cx, cy)
        block.set(cx - width / 2f * u, cy - outer * u, cx + width / 2f * u, cy - (outer - length) * u)
        canvas.drawRoundRect(block, 2f * u, 2f * u, markPaint)
        canvas.restore()
    }

    private fun pitchMaxText(): String {
        val a = maxPitchUp.roundToInt(); val b = maxPitchDown.roundToInt()
        if (a != cachedPitchUp || b != cachedPitchDown || pitchMax == null) {
            cachedPitchUp = a; cachedPitchDown = b
            pitchMax = "$maxLabel ↑$a° ↓$b°"
        }
        return pitchMax!!
    }

    private fun rollMaxText(): String {
        val a = maxRollLeft.roundToInt(); val b = maxRollRight.roundToInt()
        if (a != cachedRollLeft || b != cachedRollRight || rollMax == null) {
            cachedRollLeft = a; cachedRollRight = b
            rollMax = "$maxLabel ←$a° →$b°"
        }
        return rollMax!!
    }

    private fun withAlpha(c: Int, a: Int) =
        android.graphics.Color.argb(a, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))

    private fun carPaints(ready: Boolean, onSurface: Int, variant: Int, gold: Int, outline: Int) {
        val bodyCol = if (ready) onSurface else variant
        bodyPaint.color = bodyCol
        bodyPaint.alpha = if (ready) 235 else 110
        ringPaint.color = bodyCol
        ringPaint.alpha = bodyPaint.alpha
        lightPaint.color = gold
        lightPaint.alpha = if (ready) 255 else 110
        tyrePaint.color = outline
        tyrePaint.alpha = if (ready) 200 else 90
    }

    /** Facing left: steep screen at the nose, long fastback to the tail, gold headlight in front. */
    private fun drawSideCar(canvas: Canvas, x: Float, y: Float, deg: Float, u: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(deg)           // clockwise: the car faces left, so nose up is clockwise
        canvas.drawPath(sideBody, bodyPaint)
        canvas.drawPath(sideWindow, cutPaint)
        for (wx in SIDE_WHEELS) {
            canvas.drawCircle(wx * u, 15f * u, 14f * u, cutPaint)
            canvas.drawCircle(wx * u, 15f * u, 10.5f * u, ringPaint)
        }
        canvas.drawRoundRect(-61f * u, -2f * u, -53f * u, 4f * u, 2f * u, 2f * u, lightPaint)
        canvas.restore()
    }

    /** From behind: wide body, narrower roof, dark rear screen, gold tail lights, short tyres. */
    private fun drawRearCar(canvas: Canvas, x: Float, y: Float, deg: Float, u: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(deg)           // clockwise: right side down
        canvas.drawRoundRect(-44f * u, 18f * u, -28f * u, 32f * u, 4f * u, 4f * u, tyrePaint)
        canvas.drawRoundRect(28f * u, 18f * u, 44f * u, 32f * u, 4f * u, 4f * u, tyrePaint)
        canvas.drawPath(rearBody, bodyPaint)
        canvas.drawPath(rearWindow, cutPaint)
        canvas.drawRoundRect(-43f * u, -1f * u, -27f * u, 6f * u, 2f * u, 2f * u, lightPaint)
        canvas.drawRoundRect(27f * u, -1f * u, 43f * u, 6f * u, 2f * u, 2f * u, lightPaint)
        canvas.restore()
    }

    /**
     * Value over a label row, anchored at a bottom corner. The row is "LABEL MAX ..." on the left
     * and mirrored ("MAX ... LABEL") on the right, and shrinks as a whole when a translation is too
     * long for its half of the card.
     */
    private fun readout(
        canvas: Canvas, x: Float, bottom: Float, alignRight: Boolean,
        value: String, label: String, dir: String,
        valueCol: Int, labelCol: Int, dirCol: Int, maxRow: Float, u: Float
    ) {
        labelPaint.textSize = 22f * u
        labelPaint.letterSpacing = 3f / 22f
        dirPaint.textSize = 15f * u
        dirPaint.letterSpacing = 1f / 15f
        val gap = 10f * u
        val dirW = if (dir.isEmpty()) 0f else dirPaint.measureText(dir) + gap
        val rowW = labelPaint.measureText(label) + dirW
        if (rowW > maxRow && maxRow > 0f) {
            val k = maxRow / rowW
            labelPaint.textSize *= k
            dirPaint.textSize *= k
        }
        val labelW = labelPaint.measureText(label)
        val dirTextW = if (dir.isEmpty()) 0f else dirPaint.measureText(dir)
        val g = if (dir.isEmpty()) 0f else gap * (labelPaint.textSize / (22f * u))

        val rowBaseline = bottom - labelPaint.descent()
        labelPaint.color = labelCol
        dirPaint.color = dirCol
        if (!alignRight) {
            canvas.drawText(label, x, rowBaseline, labelPaint)
            if (dir.isNotEmpty()) canvas.drawText(dir, x + labelW + g, rowBaseline, dirPaint)
        } else {
            canvas.drawText(label, x - labelW, rowBaseline, labelPaint)
            if (dir.isNotEmpty()) canvas.drawText(dir, x - labelW - g - dirTextW, rowBaseline, dirPaint)
        }

        valuePaint.textSize = 52f * u
        valuePaint.color = valueCol
        val valueBaseline = rowBaseline + labelPaint.ascent() - 6f * u - valuePaint.descent()
        val vx = if (alignRight) x - valuePaint.measureText(value) else x
        canvas.drawText(value, vx, valueBaseline, valuePaint)
    }

    private fun build(u: Float) {
        builtFor = u
        val round = CornerPathEffect(6f * u)
        bodyPaint.pathEffect = round
        cutPaint.pathEffect = CornerPathEffect(4f * u)

        sideBody.reset()
        poly(sideBody, u, -60f, 15f, -60f, 1f, -55f, -4f, -28f, -8f, -12f, -27f, 14f, -27f,
            48f, -10f, 60f, -8f, 60f, 15f)
        sideWindow.reset()
        poly(sideWindow, u, -9f, -22f, 12f, -22f, 37f, -11f, -19f, -11f)

        rearBody.reset()
        poly(rearBody, u, -48f, 22f, -48f, -1f, -42f, -8f, -36f, -8f, -28f, -32f, 28f, -32f,
            36f, -8f, 42f, -8f, 48f, -1f, 48f, 22f)
        rearWindow.reset()
        poly(rearWindow, u, -25f, -26f, 25f, -26f, 30f, -13f, -30f, -13f)
    }

    private fun poly(path: Path, u: Float, vararg xy: Float) {
        path.moveTo(xy[0] * u, xy[1] * u)
        var i = 2
        while (i < xy.size) { path.lineTo(xy[i] * u, xy[i + 1] * u); i += 2 }
        path.close()
    }

    private var dashFor = -1f
    private var dashEffect: DashPathEffect? = null
    private fun dash(u: Float): DashPathEffect {
        if (u != dashFor || dashEffect == null) {
            dashFor = u
            dashEffect = DashPathEffect(floatArrayOf(8f * u, 6f * u), 0f)
        }
        return dashEffect!!
    }

    companion object {
        // The handoff's card, in its own pixels.
        private const val CARD_W = 576f
        private const val CARD_H = 432f
        private const val CENTER_Y = 206f

        private const val CLAMP = 45f

        /** Fixed for now (user decision); the handoff suggests making them settings later. */
        private const val PITCH_WARN = 15f
        private const val ROLL_WARN = 20f

        /** Blocks per direction, 5 deg each, to the 45 deg clamp. */
        private const val SEGMENTS = 9
        private val SIDE_WHEELS = floatArrayOf(-36f, 36f)
    }
}
