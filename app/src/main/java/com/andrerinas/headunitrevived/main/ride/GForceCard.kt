package com.andrerinas.headunitrevived.main.ride

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.sensors.GForceEstimator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * G-FORCE card: how hard the bike is accelerating or braking, on a vertical bar pinned at 0.0.
 *
 * The reading is signed at the source, but only the bar shows the direction and the number stays
 * unsigned - the same split the LEAN card uses, where the gauge carries the side and the readout
 * carries the magnitude.
 *
 * [GForceEstimator] is held only while this page is the one on screen, so swiping to another card
 * releases its sensors and its GPS listener. Android Auto keeps its own claim on the engine, so the
 * maxima still accumulate through a projected ride.
 */
class GForceCard : RideCard(ID, R.layout.item_ride_card_gforce) {

    private var gauge: GForceGaugeView? = null
    private var tvValue: TextView? = null
    private var tvMaxAcc: TextView? = null
    private var tvMaxBrake: TextView? = null

    /** Eased for display: the engine emits at ~20 Hz and the screen draws at ~60. */
    private var disp = 0f

    // Last values actually written, so a frame that changes nothing does not touch a TextView
    // (each setText can trigger a relayout, sixty times a second, on a screen shared with AA).
    private var lastCenti = Int.MIN_VALUE
    private var lastMaxAccCenti = Int.MIN_VALUE
    private var lastMaxBrakeCenti = Int.MIN_VALUE

    override fun onBind(v: View) {
        gauge = v.findViewById(R.id.gforce_gauge)
        tvValue = v.findViewById(R.id.tv_gforce)
        tvMaxAcc = v.findViewById(R.id.tv_gforce_max_acc)
        tvMaxBrake = v.findViewById(R.id.tv_gforce_max_brake)
        lastCenti = Int.MIN_VALUE
        lastMaxAccCenti = Int.MIN_VALUE
        lastMaxBrakeCenti = Int.MIN_VALUE

        // Long-press to clear the recorded maxima - the same gesture the lean gauge uses.
        gauge?.setOnLongClickListener { view ->
            GForceEstimator.resetMax()
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Toast.makeText(view.context, R.string.ride_gforce_reset, Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onUnbind() {
        gauge?.setOnLongClickListener(null)
        gauge = null
        tvValue = null
        tvMaxAcc = null
        tvMaxBrake = null
    }

    override fun onPageActive(context: Context) = GForceEstimator.acquire(OWNER, context)

    override fun onPageInactive() = GForceEstimator.release(OWNER)

    override fun render(t: RideTelemetry) {
        val s = GForceEstimator.state.value
        disp = ease(disp, s.currentG, t.frameDtSec, TAU_G)

        // Below this the reading is GPS and sensor noise rather than the bike doing anything, and
        // drawing it made the last digit flicker at a steady cruise. Squelched only on the way to
        // the screen: the estimator and the telemetry keep the raw value.
        val shown = if (abs(disp) < DISPLAY_DEADBAND) 0f else disp

        gauge?.let {
            it.value = shown
            it.maxAcc = s.maxAccG
            it.maxBrake = s.maxBrakeG
        }

        val tv = tvValue ?: return
        val centi = (abs(shown) * 100f).roundToInt()
        if (centi != lastCenti) {
            lastCenti = centi
            tv.text = format(centi)
        }

        val accCenti = (s.maxAccG * 100f).roundToInt()
        if (accCenti != lastMaxAccCenti) {
            lastMaxAccCenti = accCenti
            tvMaxAcc?.text = format(accCenti)
        }
        val brakeCenti = (s.maxBrakeG * 100f).roundToInt()
        if (brakeCenti != lastMaxBrakeCenti) {
            lastMaxBrakeCenti = brakeCenti
            tvMaxBrake?.text = format(brakeCenti)
        }
    }

    /** Fixed two decimals, from an already-rounded hundredth so the text only changes when it must. */
    private fun format(centi: Int): String =
        String.format(Locale.US, "%d.%02d", centi / 100, centi % 100)

    companion object {
        const val ID = "gforce"
        private const val OWNER = "ride-gforce"

        /** Short: the engine has already smoothed this; here we only bridge 20 Hz to 60 Hz. */
        private const val TAU_G = 0.05f

        /** ~0.25 m/s2 - below this nothing the rider did is being measured. */
        private const val DISPLAY_DEADBAND = 0.025f
    }
}
