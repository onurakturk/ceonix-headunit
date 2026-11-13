package com.andrerinas.headunitrevived.main.ride

import android.widget.TextView
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.sensors.AltitudeTracker
import kotlin.math.roundToInt

/**
 * Altitude readout and its trace, shared by the Ride sunset card and the Drive altitude/compass
 * card so the two never drift apart. Lifted out of SunsetCard unchanged.
 *
 * The raw figure moves several metres a second - that is GPS vertical accuracy, not the vehicle
 * climbing - so it is filtered on the way to the screen while [AltitudeTracker] keeps the raw
 * value, the same split GForceCard uses for its deadband.
 *
 * Three things happen here, and the look depends on all three:
 *  - an EMA takes the noise out;
 *  - the displayed metres then follow it **one step at a time**, past a metre of hysteresis, so
 *    the number creeps like a mechanical altimeter instead of jumping and never flickers
 *    between two values at a boundary;
 *  - and while the vehicle is stopped nothing moves at all, because a parked vehicle's altitude
 *    does not change - anything on screen then would be pure noise. Same reasoning as the trace's
 *    one-kilometre gate.
 */
class AltitudeReadout {

    private var tvAltitude: TextView? = null
    private var spark: AltitudeSparkView? = null

    // Smoothed altitude, and the whole metres actually on screen. Both are also the memoisation
    // keys: most of the 60 frames a second change neither view.
    private var dispAltitude = Float.NaN
    private var shownAltitude = Int.MIN_VALUE
    private var lastSparkVersion = -1

    fun bind(tv: TextView?, sparkView: AltitudeSparkView?) {
        tvAltitude = tv
        spark = sparkView
        // Cleared so the first frame back seeds the filter from the live reading: returning to this
        // card shows the right figure at once instead of easing up to it over a quarter of a minute.
        dispAltitude = Float.NaN
        shownAltitude = Int.MIN_VALUE
        lastSparkVersion = -1
    }

    fun unbind() {
        tvAltitude = null
        spark = null
    }

    fun render(frameDtSec: Float, speedKmh: Float) {
        val tv = tvAltitude ?: return

        val raw = AltitudeTracker.current
        if (raw.isNaN()) {
            if (shownAltitude != Int.MIN_VALUE) {
                shownAltitude = Int.MIN_VALUE
                tv.text = tv.context.getString(R.string.ride_no_signal)
            }
        } else {
            if (dispAltitude.isNaN()) dispAltitude = raw
            else if (speedKmh >= MOVING_KMH) {
                dispAltitude = ease(dispAltitude, raw, frameDtSec, TAU_ALTITUDE)
            }

            val before = shownAltitude
            if (shownAltitude == Int.MIN_VALUE) shownAltitude = dispAltitude.roundToInt()
            else if (dispAltitude - shownAltitude > STEP_HYSTERESIS_M) shownAltitude++
            else if (shownAltitude - dispAltitude > STEP_HYSTERESIS_M) shownAltitude--
            if (shownAltitude != before) tv.text = shownAltitude.toString()
        }

        val v = AltitudeTracker.version
        if (v != lastSparkVersion) {
            lastSparkVersion = v
            spark?.refresh()
        }
    }

    companion object {
        /**
         * Long enough to bury GPS vertical noise (about 10 m of it, down to 3), short enough that a
         * climb is not left behind: on a 5% grade at 60 km/h the figure trails by roughly ten metres.
         */
        private const val TAU_ALTITUDE = 12f

        /** The number moves only once the filtered value has genuinely drifted this far. */
        private const val STEP_HYSTERESIS_M = 1f

        /** Below this the vehicle is parked; same threshold family as GForceEstimator's park speed. */
        private const val MOVING_KMH = 3f
    }
}
