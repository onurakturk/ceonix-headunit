package com.andrerinas.headunitrevived.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class NightMode(private val settings: Settings, val hasGPSLocation: Boolean) {
    private val calculator = NightModeCalculator(settings)
    var currentLux: Float = -1f
    var currentBrightness: Int = -1

    /**
     * Pure sunrise/sunset night state, independent of [Settings.nightMode].
     * Use when a feature needs the astronomical day/night decision without being
     * affected by the user's Android Auto night-mode selection.
     */
    val isSunriseSunsetNight: Boolean
        get() = calculator.current

    var current: Boolean = false
        get()  {
            return when (settings.nightMode){
                Settings.NightMode.AUTO -> calculator.current
                Settings.NightMode.DAY -> false
                Settings.NightMode.NIGHT -> true
                Settings.NightMode.MANUAL_TIME -> {
                    val now = Calendar.getInstance()
                    val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                    val start = settings.nightModeManualStart
                    val end = settings.nightModeManualEnd
                    
                    val isNight = if (start <= end) {
                        currentMinutes in start..end
                    } else {
                        // Rollover (e.g. 22:00 to 06:00)
                        currentMinutes >= start || currentMinutes <= end
                    }
                    
                    AppLog.d("NightMode Check: Now=$currentMinutes, Start=$start, End=$end, Result=$isNight")
                    isNight
                }
                Settings.NightMode.LIGHT_SENSOR -> {
                    if (currentLux >= 0) currentLux < settings.nightModeThresholdLux else false
                }
                Settings.NightMode.SCREEN_BRIGHTNESS -> {
                    if (currentBrightness >= 0) currentBrightness < settings.nightModeThresholdBrightness else false
                }
                Settings.NightMode.CAR_SIGNAL -> false // Handled directly in NightModeManager
            }
        }

    override fun toString(): String {
        return when (settings.nightMode){
            Settings.NightMode.AUTO -> "NightMode: ${calculator.current}"
            Settings.NightMode.DAY -> "NightMode: DAY"
            Settings.NightMode.NIGHT -> "NightMode: NIGHT"
            Settings.NightMode.MANUAL_TIME -> {
                val startH = settings.nightModeManualStart / 60
                val startM = settings.nightModeManualStart % 60
                val endH = settings.nightModeManualEnd / 60
                val endM = settings.nightModeManualEnd % 60
                "NightMode: Manual (%02d:%02d - %02d:%02d)".format(startH, startM, endH, endM)
            }
            Settings.NightMode.LIGHT_SENSOR -> "NightMode: Sensor ($currentLux < ${settings.nightModeThresholdLux})"
            Settings.NightMode.SCREEN_BRIGHTNESS -> "NightMode: Brightness ($currentBrightness < ${settings.nightModeThresholdBrightness})"
            Settings.NightMode.CAR_SIGNAL -> "NightMode: Car ILL+ signal"
        }
    }

    fun getCalculationInfo(): String {
        return calculator.getCalculationInfo()
    }
}

private class NightModeCalculator(private val settings: Settings) {
    private val twilightCalculator = TwilightCalculator()
    private val format = SimpleDateFormat("HH:mm", Locale.US)

    /**
     * Where the sun is computed for. The last real GPS fix first, then the position the Android Auto
     * path stored, and only if neither has ever been written - nothing at all. The old code fell
     * back to lastKnownLocation's built-in Tel Aviv default, which is a made-up place, not a guess.
     */
    private fun location(): android.location.Location? =
        settings.lastGpsFix ?: if (settings.hasLastKnownLocation) settings.lastKnownLocation else null

    fun getCalculationInfo(): String {
        val location = location() ?: return "--:-- - --:--"
        val time = Calendar.getInstance().time
        twilightCalculator.calculateTwilight(time.time, location.latitude, location.longitude)

        val sunrise = if (twilightCalculator.mSunrise > 0) format.format(Date(twilightCalculator.mSunrise)) else "--:--"
        val sunset = if (twilightCalculator.mSunset > 0) format.format(Date(twilightCalculator.mSunset)) else "--:--"
        return "$sunrise - $sunset"
    }

    var current: Boolean = false
        get()  {
            val location = location()
            if (location == null) {
                // No position ever known: a plain clock window instead of an invented location.
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                return hour < DAY_START_HOUR || hour >= DAY_END_HOUR
            }
            val time = Calendar.getInstance().time
            twilightCalculator.calculateTwilight(time.time, location.latitude, location.longitude)
            return twilightCalculator.mState == TwilightCalculator.NIGHT
        }

    private companion object {
        const val DAY_START_HOUR = 7
        const val DAY_END_HOUR = 19
    }

    override fun toString(): String {
        val sunrise = if (twilightCalculator.mSunrise > 0) format.format(Date(twilightCalculator.mSunrise)) else "-1"
        val sunset = if (twilightCalculator.mSunset > 0) format.format(Date(twilightCalculator.mSunset)) else "-1"
        val mode = if (twilightCalculator.mState == TwilightCalculator.NIGHT) "NIGHT" else "DAY"
        return String.format(Locale.US, "%s, (%s - %s)", mode, sunrise, sunset)
    }
}