package com.andrerinas.headunitrevived.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.BatteryManager
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.location.GpsFixMonitor
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.AppThemeManager
import com.andrerinas.headunitrevived.utils.BluetoothHelper
import com.andrerinas.headunitrevived.utils.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Drives the Material 3 status bar shared by the Home and Ride screens: clock, GPS/BT indicators,
 * theme toggle and the battery pill. Home keeps its own inline copy (verified, connection-critical
 * screen — left untouched); the Ride screen uses this controller so the two bars behave identically
 * without duplicating the runtime logic there.
 *
 * Wire it once in onViewCreated via [attach], then mirror the fragment lifecycle with [start] /
 * [stop] (onResume / onPause). Expects the same view ids as the Home status bar.
 */
class StatusBarController(private val root: View) {

    private val ctx: Context get() = root.context

    private val tvClock: TextView = root.findViewById(R.id.tv_clock)
    private val gpsIndicator: ImageView = root.findViewById(R.id.gps_indicator)
    private val btIndicator: ImageView = root.findViewById(R.id.bt_indicator)
    private val themeToggle: ImageView = root.findViewById(R.id.theme_toggle)
    private val batteryView: BatteryView = root.findViewById(R.id.battery_view)
    private val tvBattery: TextView = root.findViewById(R.id.tv_battery)
    private val gpsPulse = GpsChipPulse(gpsIndicator)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) updateBattery(level * 100 / scale)
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            refreshIndicators()
            handler.postDelayed(this, 15000)
        }
    }

    /**
     * Own scope for the GPS flow. This is a plain view wrapper, not a Fragment, so there is no
     * lifecycleScope to borrow — it is created in [start] and cancelled in [stop], which the
     * host fragment already mirrors to onResume/onPause.
     */
    private var gpsScope: CoroutineScope? = null

    fun attach() {
        themeToggle.setOnClickListener { toggleTheme() }
        // Tap reports the fix state, long-press opens the system location settings — same as Home.
        gpsIndicator.setOnClickListener { announceGpsFix() }
        gpsIndicator.setOnLongClickListener {
            openSystemSettings(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            true
        }
        btIndicator.setOnClickListener {
            openSystemSettings(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        updateClock()
        refreshThemeToggle()
        refreshIndicators()
        applyGpsIndicator(GpsFixMonitor.state.value)
    }

    fun start() {
        refreshThemeToggle()
        updateClock()
        refreshIndicators()
        val intent = ctx.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        intent?.let { batteryReceiver.onReceive(ctx, it) }
        GpsFixMonitor.acquire("ride", ctx)
        gpsScope?.cancel()
        gpsScope = CoroutineScope(Dispatchers.Main.immediate).also { scope ->
            scope.launch { GpsFixMonitor.state.collect { applyGpsIndicator(it) } }
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 15000)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        try { ctx.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        gpsScope?.cancel()
        gpsScope = null
        gpsPulse.cancel()
        GpsFixMonitor.release("ride")
    }

    /** Tap feedback for the GPS chip: haptic plus the current state, same shape as the lean reset. */
    private fun announceGpsFix() {
        val msg = when (GpsFixMonitor.state.value) {
            GpsFixMonitor.GpsFix.ACQUIRED -> R.string.gps_status_acquired
            GpsFixMonitor.GpsFix.SEARCHING -> R.string.sunset_waiting
            GpsFixMonitor.GpsFix.UNAVAILABLE -> R.string.gps_status_off
        }
        gpsIndicator.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    private fun updateClock() {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        tvClock.text = fmt.format(java.util.Date())
    }

    private fun updateBattery(percent: Int) {
        val pct = percent.coerceIn(0, 100)
        batteryView.setLevel(pct)
        tvBattery.text = "$pct%"
    }

    /** The Bluetooth chip only; the GPS chip is driven by [GpsFixMonitor] instead. */
    private fun refreshIndicators() {
        val btOn = try { BluetoothHelper.getBluetoothAdapter(ctx)?.isEnabled == true } catch (e: Exception) { false }
        applyBtWarning(btOn)
    }

    /**
     * The Bluetooth chip is a warning lamp, not a status light: hidden while the adapter is on,
     * red when it is off. Bluetooth carries the entire Native AA handshake, so an adapter that is
     * off means Android Auto will never connect — and that failure is otherwise silent, since the
     * handshake manager only logs it.
     */
    private fun applyBtWarning(btOn: Boolean) {
        btIndicator.visibility = if (btOn) View.GONE else View.VISIBLE
        if (btOn) return
        btIndicator.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.m3_surface_container))
        btIndicator.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.m3_error))
        btIndicator.contentDescription = ctx.getString(R.string.bt_not_enabled)
    }

    /**
     * Three states: a fix is the accent fill, searching is a gold glyph breathing on the plain chip
     * (see [GpsChipPulse]), and no provider (or no permission) turns the glyph red — that one is a
     * fault, not just an absence, because the phone's navigation runs on this GPS.
     */
    private fun applyGpsIndicator(fix: GpsFixMonitor.GpsFix) {
        val bg = if (fix == GpsFixMonitor.GpsFix.ACQUIRED) R.color.m3_primary else R.color.m3_surface_container
        val fg = when (fix) {
            GpsFixMonitor.GpsFix.ACQUIRED -> R.color.m3_on_primary
            GpsFixMonitor.GpsFix.SEARCHING -> R.color.m3_primary
            GpsFixMonitor.GpsFix.UNAVAILABLE -> R.color.m3_error
        }
        gpsIndicator.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, bg))
        gpsIndicator.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, fg))
        gpsPulse.setSearching(fix == GpsFixMonitor.GpsFix.SEARCHING)
    }

    private fun refreshThemeToggle() {
        val theme = App.provide(ctx).settings.appTheme
        val isNight = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isAuto = theme == Settings.AppTheme.AUTOMATIC
        // Auto has nothing the icon alone can say, so it borrows the "active" look the
        // GPS/BT indicators use: accent fill, on-accent glyph.
        val bg = if (isAuto) R.color.m3_primary else R.color.m3_surface_container
        val fg = if (isAuto) R.color.m3_on_primary else R.color.m3_on_surface_variant
        themeToggle.setImageResource(AppThemeManager.toggleIconRes(theme, isNight))
        themeToggle.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, fg))
        themeToggle.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, bg))
    }

    /** Cycle the whole app through dark (DARK), light (CLEAR) and auto (AUTOMATIC); recreates activities. */
    private fun toggleTheme() {
        val appSettings = App.provide(ctx).settings
        val isNight = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val next = AppThemeManager.nextToggleTheme(appSettings.appTheme, isNight)
        appSettings.appTheme = next
        // The same reset DarkModeFragment does when a pinned mode is picked: coming from a
        // dynamic mode this flag could stay on and leave the Settings screens extra dark.
        if (next == Settings.AppTheme.CLEAR || next == Settings.AppTheme.DARK) {
            appSettings.useExtremeDarkMode = false
        }
        App.appThemeManager?.stop()
        App.appThemeManager = null
        AppThemeManager.applyStaticTheme(appSettings)
        AppThemeManager.signalVisualChange()
    }

    private fun openSystemSettings(action: String) {
        try {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.w("StatusBarController: cannot open $action: ${e.message}")
        }
    }
}
