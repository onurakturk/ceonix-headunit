package com.andrerinas.headunitrevived.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reports whether the head unit currently has a usable GPS fix, for the status bar chip.
 *
 * This is deliberately separate from [GpsLocation]/[GpsLocationService]: those feed the phone
 * over the Android Auto sensor channel and only run while a session is up. This one answers a
 * different question — "is navigation ready right now" — and has to work on the home screen
 * with nothing connected.
 *
 * Screens [acquire]/[release] it and observe [state], the same reference-counted shape
 * `LeanEstimator` uses. The listener exists only while at least one screen holds it.
 *
 * On the Ride screen this shares the provider with `RideFragment`'s own 250 ms listener;
 * Android merges the two requests and the tighter interval wins, so watching from here costs
 * nothing extra there.
 */
object GpsFixMonitor {

    enum class GpsFix {
        /** No location permission, or the GPS provider is switched off. */
        UNAVAILABLE,
        /** Provider is on, but no fix has arrived recently. */
        SEARCHING,
        /** A fix arrived within [FIX_STALE_MS]. */
        ACQUIRED
    }

    /** How often we ask for a fix while a screen is watching. */
    private const val UPDATE_INTERVAL_MS = 5_000L

    /**
     * How old the last fix may be and still count as ACQUIRED. Three missed updates.
     * (LeanEstimator's 2500 ms is tuned to its own 200 ms listener and does not apply here.)
     */
    private const val FIX_STALE_MS = 15_000L

    /** Re-evaluate on a tick too: when the fix dies the listener simply goes quiet. */
    private const val TICK_MS = 5_000L

    private val _state = MutableStateFlow(GpsFix.UNAVAILABLE)
    val state: StateFlow<GpsFix> = _state

    private val owners = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var locationManager: LocationManager? = null
    private var running = false
    private var lastFixMs = 0L

    /**
     * Persisting the last real fix (Settings.lastGpsFix) lives here because this is the one GPS
     * listener that runs whenever the app is in front - Android Auto included, thanks to App's
     * keep-alive - so no new listener is needed. Written on an interval rather than "on close":
     * cutting the ignition does not guarantee a clean shutdown, and the worst this loses is the
     * last minute of a ride that was already slowing down to park.
     */
    private const val PERSIST_INTERVAL_MS = 60_000L
    private const val PERSIST_MAX_ACCURACY_M = 100f
    private var pendingFix: Location? = null
    private var lastPersistMs = 0L

    private val listener = LocationListener { loc: Location ->
        lastFixMs = SystemClock.elapsedRealtime()
        if (loc.hasAccuracy() && loc.accuracy <= PERSIST_MAX_ACCURACY_M) {
            pendingFix = loc
            if (lastFixMs - lastPersistMs >= PERSIST_INTERVAL_MS) persistPendingFix()
        }
        publish()
    }

    private fun persistPendingFix() {
        val loc = pendingFix ?: return
        val ctx = appContext ?: return
        try {
            App.provide(ctx).settings.lastGpsFix = loc
            pendingFix = null
            lastPersistMs = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            AppLog.w("GpsFixMonitor: persisting the last fix failed: ${e.message}")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            publish()
            handler.postDelayed(this, TICK_MS)
        }
    }

    @Synchronized
    fun acquire(owner: String, context: Context) {
        appContext = context.applicationContext
        owners.add(owner)
        if (!running) start()
    }

    @Synchronized
    fun release(owner: String) {
        owners.remove(owner)
        if (owners.isEmpty()) stop()
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        val ctx = appContext ?: return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = lm

        // Seed from the last known fix so a screen that opens right after another one closed
        // does not flash "searching" for nothing.
        if (hasPermission(ctx)) {
            try {
                val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (last != null) {
                    val ageMs = (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 1_000_000L
                    if (ageMs in 0 until FIX_STALE_MS) lastFixMs = SystemClock.elapsedRealtime() - ageMs
                }
            } catch (e: Exception) {
                AppLog.w("GpsFixMonitor: last known location failed: ${e.message}")
            }
            try {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, listener, Looper.getMainLooper()
                )
            } catch (e: Exception) {
                AppLog.w("GpsFixMonitor: GPS start failed: ${e.message}")
            }
        }

        running = true
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
        publish()
        AppLog.i("GpsFixMonitor started")
    }

    private fun stop() {
        handler.removeCallbacks(tick)
        try { locationManager?.removeUpdates(listener) } catch (_: Exception) {}
        persistPendingFix()
        running = false
        AppLog.i("GpsFixMonitor stopped")
    }

    private fun publish() {
        val ctx = appContext ?: return
        val lm = locationManager
        val providerOn = try {
            lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        } catch (e: Exception) { false }

        val next = when {
            !hasPermission(ctx) || !providerOn -> GpsFix.UNAVAILABLE
            lastFixMs > 0L && SystemClock.elapsedRealtime() - lastFixMs < FIX_STALE_MS -> GpsFix.ACQUIRED
            else -> GpsFix.SEARCHING
        }
        if (next != _state.value) {
            AppLog.i("GPS: fix state ${_state.value} -> $next")
            _state.value = next
        }
    }

    private fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
