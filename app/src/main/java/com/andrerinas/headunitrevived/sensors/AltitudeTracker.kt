package com.andrerinas.headunitrevived.sensors

import android.location.Location
import android.os.Build

/**
 * Rolling altitude history for the Ride screen, kept application-scoped.
 *
 * It owns **no GPS listener of its own**. There are already four on this device (RideFragment,
 * GpsFixMonitor, LeanEstimator, GForceEstimator) and a fifth would buy nothing, so this is a
 * passive store fed from the two places that are already listening:
 *
 *  - [com.andrerinas.headunitrevived.main.ride.SunsetCard] while the Ride screen is open, and
 *  - [GForceEstimator] while Android Auto is projecting, since that engine is held by App for the
 *    length of a projected ride.
 *
 * Feeding it twice is harmless: the sampling gate below decides what actually lands in the buffer.
 *
 * Application-scoped rather than owned by the card because RideCards builds fresh card objects for
 * every controller, so a buffer living in the card would be wiped by a theme change or by leaving
 * and re-entering the Ride screen. It is deliberately not persisted - the history describes the
 * ride you are on, and starts empty on a cold launch.
 */
object AltitudeTracker {

    /**
     * Sampled by distance rather than time, so waiting at a light does not fill the window with a
     * flat line - what gets drawn is a route profile, and a long stop leaves it exactly where it
     * was instead of scrolling the ride off the left edge.
     *
     * A kilometre between samples works out to roughly a minute at normal pace, which is about as
     * often as terrain worth drawing actually changes: this is a head unit on a bike, not a hiking
     * instrument. Forty of them cover 40 km - a leg between stops - and land about 9 px apart on
     * the sparkline, where the old 75 would have crowded them to 5.
     */
    private const val SAMPLE_DISTANCE_M = 1000f
    private const val CAPACITY = 40

    private val ring = FloatArray(CAPACITY)
    private var count = 0
    private var head = 0                    // next write position
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private val distResult = FloatArray(1)  // reused; distanceBetween writes into it

    /** Latest altitude in metres, or NaN before the first usable fix. */
    @Volatile
    var current: Float = Float.NaN
        private set

    /** Bumped on every stored sample, so a view can redraw only when the trace actually grew. */
    @Volatile
    var version: Int = 0
        private set

    @Synchronized
    fun onLocation(loc: Location) {
        val m = altitudeOf(loc) ?: return
        current = m

        if (!lastLat.isNaN()) {
            Location.distanceBetween(lastLat, lastLon, loc.latitude, loc.longitude, distResult)
            if (distResult[0] < SAMPLE_DISTANCE_M) return
        }
        lastLat = loc.latitude
        lastLon = loc.longitude

        ring[head] = m
        head = (head + 1) % CAPACITY
        if (count < CAPACITY) count++
        version++
    }

    /** Copies the most recent samples into [out], oldest first. Returns how many were written. */
    @Synchronized
    fun snapshot(out: FloatArray): Int {
        val n = if (count < out.size) count else out.size
        val start = (head - n + CAPACITY) % CAPACITY
        for (i in 0 until n) out[i] = ring[(start + i) % CAPACITY]
        return n
    }

    /**
     * Height above mean sea level where the platform can supply it, raw WGS84 ellipsoid height
     * otherwise.
     *
     * The plain `altitude` field is measured from the ellipsoid, which in Turkey sits some 35-40 m
     * from the figure on a map or a road sign. Android 14 added a properly corrected value, so a
     * modern head unit reports what the rider expects while an older one keeps the offset. That
     * means two devices at the same spot can disagree by roughly that much - known and accepted.
     *
     * Also used by Settings.lastGpsFix, so the persisted altitude follows the same rule.
     */
    fun altitudeOf(loc: Location): Float? {
        if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) return loc.mslAltitudeMeters.toFloat()
        if (loc.hasAltitude()) return loc.altitude.toFloat()
        return null
    }
}
