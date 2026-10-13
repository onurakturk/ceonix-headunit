package com.andrerinas.headunitrevived.utils

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Astronomical sunset/sunrise for a given latitude/longitude and date — the classic "Sunrise/Sunset
 * Algorithm" (Almanac for Computers / NOAA). No extra sensors: just the GPS position and the clock.
 * Accurate to about a minute, which is plenty for a "turn your headlights on" hint.
 */
object SunTimes {

    private const val ZENITH = 90.833          // official sunset (sun's upper limb at horizon + refraction)

    /** Epoch millis of sunset for the local date of [cal] at [lat]/[lon]; null on a polar day/night. */
    fun sunset(lat: Double, lon: Double, cal: Calendar = Calendar.getInstance()): Long? =
        event(lat, lon, cal, rising = false)

    /** Epoch millis of sunrise for the local date of [cal]; null on a polar day/night. */
    fun sunrise(lat: Double, lon: Double, cal: Calendar = Calendar.getInstance()): Long? =
        event(lat, lon, cal, rising = true)

    private fun event(lat: Double, lon: Double, cal: Calendar, rising: Boolean): Long? {
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val n = dayOfYear(year, month, day)

        val lngHour = lon / 15.0
        val t = if (rising) n + ((6 - lngHour) / 24.0) else n + ((18 - lngHour) / 24.0)

        val m = (0.9856 * t) - 3.289
        var l = m + (1.916 * sinD(m)) + (0.020 * sinD(2 * m)) + 282.634
        l = mod(l, 360.0)

        var ra = atanD(0.91764 * tanD(l))
        ra = mod(ra, 360.0)
        // Put the right ascension in the same quadrant as the true longitude.
        val lQuad = floor(l / 90.0) * 90.0
        val raQuad = floor(ra / 90.0) * 90.0
        ra = (ra + (lQuad - raQuad)) / 15.0

        val sinDec = 0.39782 * sinD(l)
        val cosDec = cos(asin(sinDec))
        val cosH = (cosD(ZENITH) - (sinDec * sinD(lat))) / (cosDec * cosD(lat))
        if (cosH > 1 || cosH < -1) return null   // sun never rises / never sets on this date

        var h = if (rising) 360.0 - acosD(cosH) else acosD(cosH)
        h /= 15.0

        val localT = h + ra - (0.06571 * t) - 6.622
        val ut = mod(localT - lngHour, 24.0)     // event time in UTC hours

        // Build the UTC instant for this calendar date at 'ut' hours.
        val utc = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(year, month - 1, day, 0, 0, 0)
        utc.add(Calendar.MILLISECOND, (ut * 3600_000.0).toInt())
        return utc.timeInMillis
    }

    private fun dayOfYear(year: Int, month: Int, day: Int): Int {
        val c = GregorianCalendar(year, month - 1, day)
        return c.get(Calendar.DAY_OF_YEAR)
    }

    private fun mod(a: Double, b: Double): Double { val r = a % b; return if (r < 0) r + b else r }
    private fun sinD(deg: Double) = sin(Math.toRadians(deg))
    private fun cosD(deg: Double) = cos(Math.toRadians(deg))
    private fun tanD(deg: Double) = tan(Math.toRadians(deg))
    private fun atanD(x: Double) = Math.toDegrees(atan(x))
    private fun acosD(x: Double) = Math.toDegrees(acos(x))
}
