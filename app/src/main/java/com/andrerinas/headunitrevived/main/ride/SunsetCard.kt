package com.andrerinas.headunitrevived.main.ride

import android.location.Location
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.sensors.AltitudeTracker
import com.andrerinas.headunitrevived.utils.SunTimes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * ALTITUDE + SUNSET card - two readouts sharing the slot, separated by a rule.
 *
 * Sunset: time (24h), countdown, an ~1-hour "getting dark" colour warning, and a "headlights on"
 * state once the sun is down. Recomputed on each fix - from GPS while riding, or from a coarse
 * network position when GPS cannot lock (indoors). That logic is untouched since it was lifted out
 * of RideFragment; only the layout around it shrank to make room for the altitude block.
 *
 * Altitude comes from [AltitudeTracker], which this card also feeds. The tracker is
 * application-scoped, so the trace keeps filling while Android Auto is projecting and is already
 * populated when the rider swipes back here.
 *
 * NOTE the id stays "sunset" even though the card now shows two things: RideCards documents it as a
 * persisted wire format, and changing it would silently reset every rider page choice.
 */
class SunsetCard : RideCard(ID, R.layout.item_ride_card_sunset) {

    private var tvSunsetTime: TextView? = null
    private var tvSunsetSub: TextView? = null
    private var ivHeadlight: View? = null
    private var tvAltitude: TextView? = null
    private var spark: AltitudeSparkView? = null

    // Smoothed altitude, and the whole metres actually on screen. Both are also the memoisation
    // keys: most of the 60 frames a second change neither view.
    private var dispAltitude = Float.NaN
    private var shownAltitude = Int.MIN_VALUE
    private var lastSparkVersion = -1

    private var haveLocation = false
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var sunsetMillis: Long? = null
    private var sunriseMillis: Long? = null
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())  // 24-hour

    override fun onBind(v: View) {
        tvSunsetTime = v.findViewById(R.id.tv_sunset_time)
        tvSunsetSub = v.findViewById(R.id.tv_sunset_sub)
        ivHeadlight = v.findViewById(R.id.iv_headlight)
        tvAltitude = v.findViewById(R.id.tv_altitude)
        spark = v.findViewById(R.id.altitude_spark)
        // Cleared so the first frame back seeds the filter from the live reading: returning to this
        // card shows the right figure at once instead of easing up to it over a quarter of a minute.
        dispAltitude = Float.NaN
        shownAltitude = Int.MIN_VALUE
        lastSparkVersion = -1
    }

    override fun onUnbind() {
        tvSunsetTime = null
        tvSunsetSub = null
        ivHeadlight = null
        tvAltitude = null
        spark = null
    }

    override fun onLocation(loc: Location, precise: Boolean) {
        // Feeds the app-scoped store; it decides what actually lands in the history.
        AltitudeTracker.onLocation(loc)

        lastLat = loc.latitude
        lastLon = loc.longitude
        haveLocation = true
        // Recompute today's sunset + sunrise for the current position (cheap; the render loop ticks).
        sunsetMillis = SunTimes.sunset(lastLat, lastLon, Calendar.getInstance())
        sunriseMillis = SunTimes.sunrise(lastLat, lastLon, Calendar.getInstance())
    }

    override fun render(t: RideTelemetry) {
        renderAltitude(t)

        val tvTime = tvSunsetTime ?: return
        val tvSub = tvSunsetSub ?: return
        val headlight = ivHeadlight ?: return
        val ctx = tvTime.context

        val sunsetCol = ContextCompat.getColor(ctx, R.color.m3_sunset)
        val neutralCol = ContextCompat.getColor(ctx, R.color.m3_on_surface)

        if (!haveLocation) {
            tvTime.text = ctx.getString(R.string.ride_no_signal)
            tvSub.text = ctx.getString(R.string.sunset_waiting)
            tvTime.setTextColor(neutralCol)
            tvSub.setTextColor(sunsetCol)
            headlight.visibility = View.GONE
            return
        }
        val ss = sunsetMillis
        if (ss == null) { // polar day/night: no sunset today
            tvTime.text = ctx.getString(R.string.ride_no_signal)
            tvSub.text = ctx.getString(R.string.sunset_none)
            tvTime.setTextColor(neutralCol)
            tvSub.setTextColor(sunsetCol)
            headlight.visibility = View.GONE
            return
        }

        tvTime.text = timeFmt.format(Date(ss))
        val now = t.nowMillis
        val diffMin = ((ss - now) / 60000L).toInt()
        // Daytime = between today's sunrise and today's sunset (sun is up). Everything else is night,
        // so the "ride safe" message stays up from sunset all the way to the next sunrise - instead of
        // flipping back to a countdown right after midnight. Falls back to the old rule at the poles.
        val sr = sunriseMillis
        val daytime = if (sr != null) now >= sr && now < ss else now < ss
        if (daytime) {
            // before sunset - sunset icon alone; the time turns warm ~1h out
            val h = diffMin / 60; val m = diffMin % 60
            tvSub.text = if (h > 0) ctx.getString(R.string.sunset_remaining_hm, h, m)
                         else ctx.getString(R.string.sunset_remaining_m, m)
            // Until ~1h out both the time and the countdown stay neutral; then both turn warm.
            val col = if (diffMin <= 60) sunsetCol else neutralCol
            tvTime.setTextColor(col)
            tvSub.setTextColor(col)
            headlight.visibility = View.GONE
        } else {
            // after sunset (until sunrise) - the rider knows it is dark; just a safety message,
            // and a green "headlights on" icon joins the sunset icon.
            tvSub.text = ctx.getString(R.string.sunset_night_msg)
            tvTime.setTextColor(sunsetCol)
            tvSub.setTextColor(sunsetCol)
            headlight.visibility = View.VISIBLE
        }
    }

    /**
     * Altitude readout and its trace.
     *
     * The raw figure moves several metres a second - that is GPS vertical accuracy, not the bike
     * climbing - so it is filtered on the way to the screen while [AltitudeTracker] keeps the raw
     * value, the same split GForceCard uses for its deadband.
     *
     * Three things happen here, and the look depends on all three:
     *  - an EMA takes the noise out;
     *  - the displayed metres then follow it **one step at a time**, past a metre of hysteresis, so
     *    the number creeps like a mechanical altimeter instead of jumping and never flickers
     *    between two values at a boundary;
     *  - and while the bike is stopped nothing moves at all, because a parked bike's altitude does
     *    not change - anything on screen then would be pure noise. Same reasoning as the trace's
     *    one-kilometre gate.
     */
    private fun renderAltitude(t: RideTelemetry) {
        val tv = tvAltitude ?: return

        val raw = AltitudeTracker.current
        if (raw.isNaN()) {
            if (shownAltitude != Int.MIN_VALUE) {
                shownAltitude = Int.MIN_VALUE
                tv.text = tv.context.getString(R.string.ride_no_signal)
            }
        } else {
            if (dispAltitude.isNaN()) dispAltitude = raw
            else if (t.speedKmh >= MOVING_KMH) {
                dispAltitude = ease(dispAltitude, raw, t.frameDtSec, TAU_ALTITUDE)
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
        const val ID = "sunset"

        /**
         * Long enough to bury GPS vertical noise (about 10 m of it, down to 3), short enough that a
         * climb is not left behind: on a 5% grade at 60 km/h the figure trails the bike by roughly
         * ten metres.
         */
        private const val TAU_ALTITUDE = 12f

        /** The number moves only once the filtered value has genuinely drifted this far. */
        private const val STEP_HYSTERESIS_M = 1f

        /** Below this the bike is parked; same threshold family as GForceEstimator's park speed. */
        private const val MOVING_KMH = 3f
    }
}
