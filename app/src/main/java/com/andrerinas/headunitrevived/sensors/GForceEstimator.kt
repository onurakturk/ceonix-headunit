package com.andrerinas.headunitrevived.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Estimates longitudinal g - how hard the bike is accelerating or braking - without any assumption
 * about how the phone is mounted. An application-scoped singleton, so it keeps accumulating maxima
 * while Android Auto projects; screens [acquire]/[release] it and observe [state]. Same shape as
 * [LeanEstimator], deliberately.
 *
 * ## Why GPS leads and the accelerometer only fills in
 * A phone cannot know which way is "forward" on its own, and this one has to work clamped to a bar
 * at an arbitrary angle *or* loose in a jacket pocket. GPS speed is the one signal immune to that:
 * its derivative is longitudinal acceleration no matter how the handset is lying. So GPS is the
 * anchor and the linear accelerometer only supplies the detail between fixes:
 *
 *  1. **Anchor** - dv/dt from consecutive GPS fixes (~4 Hz, mount-independent, drift-free).
 *  2. **Horizontal plane** - the gravity vector gives "down", so the linear acceleration is split
 *     into its horizontal part. Lean does not disturb this: the forward axis *is* the roll axis, so
 *     banking rotates the bike around it rather than moving it. (That is the trap which cost
 *     LeanEstimator a rewrite in v1.0.33, and it simply does not apply to this axis.)
 *  3. **Forward axis, learned, never calibrated** - while the GPS says the bike is speeding up or
 *     slowing down, the horizontal acceleration measured over that same interval is accumulated
 *     weighted by the GPS acceleration. Cornering force is orthogonal to longitudinal acceleration,
 *     so it cancels itself out of that sum instead of having to be filtered away. How consistently
 *     the sum points one way *is* the confidence.
 *  4. **Fusion** - a complementary filter: the GPS anchor supplies the whole slow part, and the
 *     accelerometer only the band above it, weighted by confidence. At zero confidence (phone in a
 *     pocket, or nothing learned yet) the output is the GPS derivative: blunter, still honest.
 *  5. **EMA** on the way out, so engine vibration does not make the bar twitch.
 *
 * A telemetry gauge, not a safety instrument. [G_SIGN] flips the direction in one line if a field
 * test shows acceleration and braking swapped - the escape hatch LeanEstimator needed in v1.0.24.
 */
object GForceEstimator : SensorEventListener {

    data class GForceState(
        val currentG: Float = 0f,      // + accelerating, - braking
        val maxAccG: Float = 0f,
        val maxBrakeG: Float = 0f,     // stored as a positive magnitude
        val active: Boolean = false,   // a fresh fix, and the bike is not sitting still
        val confident: Boolean = false // forward axis learned; the reading is at full detail
    )

    private val _state = MutableStateFlow(GForceState())
    val state: StateFlow<GForceState> = _state

    // ----- tunables (field-adjustable) -----
    private const val G = 9.80665f
    private const val EMIT_INTERVAL_MS = 50L    // ~20 Hz output
    private const val GPS_STALE_MS = 3000L
    private const val PARK_SPEED_MPS = 0.8f     // GPS speed noise floor; below this we call it stopped
    private const val PARK_TIME_MS = 1200L

    // Crossover chain. The anchor carries everything below CROSS_TAU and the accelerometer
    // everything above, so a mismatch leaves either a dip or a bump right in the band the rider
    // notices most. GPS_ACC_TAU and CROSS_TAU are a pair; tune them together.
    //
    // Measured on the Mi 9T, 8 September: the receiver delivers a speed sample every ~1.1 s, not
    // the 5 Hz this chain was originally written for. That is why the anchor was clipping - the
    // old fixed alpha of 0.60 works out to a 0.67 s time constant at 1 Hz instead of the intended
    // ~0.13 s, and the measured anchor lag came out at 0.60 s (first reported as 0.50 s by
    // assuming 50 ms telemetry rows; they are 60 ms). Expressing it as a time
    // constant makes the filter mean the same thing whatever the receiver's rate turns out to be.
    private const val OUT_TAU = 0.12f           // output EMA - the "smooth feel", nothing more
    private const val GPS_ACC_TAU = 0.35f       // low-pass on the GPS derivative (the anchor)
    // Above the anchor's own reach. The 0.35 s filter sits on top of a ~1 s sampling window, so the
    // anchor cannot describe anything faster than roughly a second; at the old 0.50 s the fast band
    // started too early and the 0.5-1.2 s span - the exact length of a braking manoeuvre - belonged
    // to neither source. Field logs put gHigh's contribution at a peak at just 0.063 g against the
    // anchor's 0.39 g shortfall.
    private const val CROSS_TAU = 1.0f          // where the anchor stops and the accelerometer starts
    private const val FAST_TAU = 0.15f          // vibration filter, kept below the crossover
    private const val CONF_FULL = 0.40f         // confidence at which the fast band counts in full
    private const val MIN_GPS_DT = 0.05f        // ignore absurdly close fixes
    private const val MAX_GPS_DT = 2.0f         // and stale ones: the gap makes the derivative meaningless

    private const val FWD_LEARN_MIN_ACC = 0.8f  // m/s^2: learn only from clear pulls and stops
    // Applied once per GPS fix, so the half-life follows the receiver's rate: about 13 minutes at
    // the ~1 Hz this device actually delivers (it would be 2.3 minutes at 5 Hz). The old 0.995
    // forgot roughly ten times faster, which is why a whole ride never got the axis past a
    // confidence of 0.49; at 0.999 an 8 September ride reached 0.65 with 88% of samples above 0.6.
    private const val FWD_FORGET = 0.999f
    private const val FWD_MIN_ENERGY = 2.0f     // until this much evidence exists, confidence stays 0
    private const val MAX_PLAUSIBLE_G = 2.0f    // beyond this it is a sensor glitch, not a rider
    private const val GRAVITY_LP = 0.02f        // fallback path only: slow low-pass that becomes gravity

    /** Flip in one line if a field test shows acceleration and braking swapped. */
    private const val G_SIGN = 1f

    // ----- runtime -----
    private var appContext: Context? = null
    private val owners = HashSet<String>()
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null

    /** True when the device offers LINEAR_ACCELERATION + GRAVITY; otherwise we split them ourselves. */
    private var haveCompositeSensors = false

    private val gravity = FloatArray(3)
    private var haveGravity = false
    private val linear = FloatArray(3)
    private var haveLinear = false

    /** Horizontal part of the linear acceleration, and its running sum over the GPS interval. */
    private val aH = FloatArray(3)
    private val aHSum = FloatArray(3)
    private var aHCount = 0

    /** Learned forward axis in device coordinates, and the evidence behind it. */
    private val fwdAccum = FloatArray(3)
    private val fwdRef = FloatArray(3)
    private var fwdEnergy = 0f
    private var fwdConfidence = 0f

    private var speedMps = 0f
    private var lastSpeedMps = 0f
    private var lastGpsMs = 0L
    private var lastGpsSampleMs = 0L
    private var gGps = 0f                        // GPS-derived longitudinal g (the anchor)

    private var gFast = 0f                       // accelerometer-derived g along the learned axis
    private var gFastDC = 0f                     // its slow part, subtracted so only the fast band is used
    private var gFused = 0f
    private var outG = 0f

    private var lastStepNs = 0L
    private var lastEmitMs = 0L
    private var parkedSince = 0L
    private var activeRiding = false

    private var maxAcc = 0f
    private var maxBrake = 0f

    /** Latest smoothed reading, for the shared telemetry CSV. Cheap enough to poll per row. */
    val currentG: Float get() = outG

    /** The bare GPS anchor and how far the forward axis has been learned - telemetry columns. */
    val gpsG: Float get() = gGps
    val confidence: Float get() = fwdConfidence

    // ===== lifecycle =====

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

    private fun start() {
        val ctx = appContext ?: return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sensorManager = sm

        // Restore the persisted session maxima (survive a reboot; cleared only by resetMax).
        try {
            val s = App.provide(ctx).settings
            maxAcc = s.gforceMaxAcc / 1000f
            maxBrake = s.gforceMaxBrake / 1000f
        } catch (_: Exception) {}

        val lin = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        haveCompositeSensors = lin != null && grav != null
        if (haveCompositeSensors) {
            sm.registerListener(this, lin, SensorManager.SENSOR_DELAY_GAME, handler)
            sm.registerListener(this, grav, SensorManager.SENSOR_DELAY_GAME, handler)
        } else {
            // Older hardware without the composite sensors: split gravity out of the raw accelerometer.
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
            }
        }

        locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, locListener, Looper.getMainLooper())
            } catch (e: Exception) { AppLog.w("GForceEstimator: GPS start failed: " + e.message) }
        }

        running = true
        lastStepNs = 0L
        lastGpsSampleMs = 0L
        AppLog.i("GForceEstimator started (GPS-anchored, composite sensors=" + haveCompositeSensors + ")")
    }

    private fun stop() {
        sensorManager?.unregisterListener(this)
        try { locationManager?.removeUpdates(locListener) } catch (_: Exception) {}
        running = false
        // Freeze the reading but keep the recorded maxima for when a screen comes back.
        _state.value = _state.value.copy(currentG = 0f, active = false)
        outG = 0f
        AppLog.i("GForceEstimator stopped")
    }

    /** Reset the recorded maxima (long-press on the gauge). Does not touch the learned axis. */
    fun resetMax() {
        maxAcc = 0f
        maxBrake = 0f
        persistMax()
        emit(force = true)
    }

    private fun persistMax() {
        val ctx = appContext ?: return
        try {
            val s = App.provide(ctx).settings
            s.gforceMaxAcc = (maxAcc * 1000f).roundToInt()
            s.gforceMaxBrake = (maxBrake * 1000f).roundToInt()
        } catch (_: Exception) {}
    }

    // ===== sensors =====

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
                haveGravity = true
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                System.arraycopy(event.values, 0, linear, 0, 3)
                haveLinear = true
                step(event.timestamp)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // Fallback path: a slow low-pass is gravity, the remainder is the linear part.
                if (!haveGravity) {
                    System.arraycopy(event.values, 0, gravity, 0, 3); haveGravity = true
                } else {
                    for (i in 0..2) gravity[i] += GRAVITY_LP * (event.values[i] - gravity[i])
                }
                for (i in 0..2) linear[i] = event.values[i] - gravity[i]
                haveLinear = true
                step(event.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** One fusion step, driven by the fast, steady accelerometer stream. */
    private fun step(eventNs: Long) {
        if (!haveGravity || !haveLinear) return
        val dt = if (lastStepNs == 0L) 0.02f else ((eventNs - lastStepNs) / 1e9f).coerceIn(0f, 0.1f)
        lastStepNs = eventNs

        // Horizontal component of the linear acceleration: strip whatever points along gravity.
        val gn = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (gn < 1e-3f) return
        val ux = gravity[0] / gn; val uy = gravity[1] / gn; val uz = gravity[2] / gn
        val along = linear[0] * ux + linear[1] * uy + linear[2] * uz
        aH[0] = linear[0] - along * ux
        aH[1] = linear[1] - along * uy
        aH[2] = linear[2] - along * uz

        // Accumulate over the current GPS interval, so both sources describe the same window.
        aHSum[0] += aH[0]; aHSum[1] += aH[1]; aHSum[2] += aH[2]
        aHCount++

        val now = SystemClock.elapsedRealtime()
        val gpsFresh = now - lastGpsMs < GPS_STALE_MS

        // Fast signal: how much of the horizontal acceleration lies along the learned forward axis,
        // low-passed at source. The raw projection carries road and engine vibration straight into
        // the fusion, which is what made a steady cruise twitch by ~0.02 g between frames. Cutting
        // it here costs no perceptible lag: what it removes sits well above any real change in how
        // hard the bike is pulling.
        val gFastRaw = if (fwdConfidence > 0f)
            (aH[0] * fwdRef[0] + aH[1] * fwdRef[1] + aH[2] * fwdRef[2]) / G
        else 0f
        gFast += (dt / (FAST_TAU + dt)).coerceIn(0f, 1f) * (gFastRaw - gFast)

        // Fusion, as a proper complementary filter: the GPS anchor supplies everything slow and the
        // accelerometer only the band above it.
        //
        // It used to integrate the fast signal's changes and pull the result back toward GPS, which
        // failed on both counts. An integrator accumulates sensor noise, and the pull-back time
        // constant grew with confidence, so the better the learned axis got the *later* the reading
        // arrived. A 6 September ride measured it: during steady engine braking the bare anchor sat
        // at 87% of true and moved 0.0016 g between frames, while the fused output sat at 61% and
        // moved 0.0097 g - six times jumpier than its own input.
        //
        // Taking the DC term straight from GPS fixes all of it. The amplitude is the anchor's, so it
        // cannot be clipped; gHigh carries no DC, so it cannot drift; and the fast component passes
        // through immediately instead of waiting on a pull-back.
        gFastDC += (dt / (CROSS_TAU + dt)).coerceIn(0f, 1f) * (gFast - gFastDC)
        val gHigh = gFast - gFastDC

        // Confidence now weights how much of that band is trusted, rather than how long we may
        // drift from GPS. At zero confidence the output simply *is* the anchor - which the same
        // ride showed to be the cleanest signal available.
        val confGain = (fwdConfidence / CONF_FULL).coerceIn(0f, 1f)
        gFused = gGps + gHigh * confGain

        updateActivation(now)
        val parked = parkedSince != 0L && now - parkedSince > PARK_TIME_MS
        val desired = if (activeRiding && gpsFresh && !parked) gFused * G_SIGN else 0f

        outG += (dt / (OUT_TAU + dt)).coerceIn(0f, 1f) * (desired - outG)
        if (!activeRiding && abs(outG) < 0.005f) outG = 0f

        // Maxima only accumulate while actually riding, so a session cannot be polluted by a reading
        // taken while parked - the counterpart of the stale-lean-max lesson from v1.0.47.
        if (activeRiding && !parked && abs(outG) < MAX_PLAUSIBLE_G) {
            var newMax = false
            if (outG > maxAcc) { maxAcc = outG; newMax = true }
            if (-outG > maxBrake) { maxBrake = -outG; newMax = true }
            if (newMax) persistMax()
        }

        if (now - lastEmitMs >= EMIT_INTERVAL_MS) emit()
    }

    private fun updateActivation(now: Long) {
        // No speed hysteresis here, unlike LeanEstimator: a launch from a standstill is exactly the
        // moment worth showing, so the gate is "we have a fix and the bike is not sitting still".
        activeRiding = now - lastGpsMs < GPS_STALE_MS
        if (speedMps < PARK_SPEED_MPS) {
            if (parkedSince == 0L) parkedSince = now
        } else {
            parkedSince = 0L
        }
    }

    private fun emit(force: Boolean = false) {
        lastEmitMs = SystemClock.elapsedRealtime()
        val s = GForceState(
            currentG = outG,
            maxAccG = maxAcc,
            maxBrakeG = maxBrake,
            active = activeRiding,
            confident = fwdConfidence > 0.5f
        )
        if (force || s != _state.value) _state.value = s
    }

    // ===== GPS: the anchor, and the forward-axis lesson =====

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = SystemClock.elapsedRealtime()
            lastGpsMs = now

            // Keep the Ride altitude trace filling while Android Auto is projecting: this engine is
            // already held for the length of a projected ride, and the store ignores what it does
            // not need.
            AltitudeTracker.onLocation(location)

            if (!location.hasSpeed()) return
            val v = location.speed

            val dt = (now - lastGpsSampleMs) / 1000f
            val usable = lastGpsSampleMs != 0L && dt >= MIN_GPS_DT && dt <= MAX_GPS_DT
            lastGpsSampleMs = now

            if (usable) {
                // Anchor. The step out of a standstill is not trusted: while the bike waits, the
                // receiver keeps delivering fixes but holds the speed at zero, then jumps straight
                // to the real figure once it decides the bike is moving. On 13 September that jump
                // was 0 -> 37 km/h in one ~1 s interval - 1.05 g of acceleration that never
                // happened - and it put 0.91 g into the recorded maximum and into the forward-axis
                // lesson. Nine pulls away that day showed the same shape.
                //
                // Only the step *out* of a standstill is dropped; a stop is still counted in full,
                // so braking is untouched. The first second of a genuine launch is carried by the
                // accelerometer band instead, and the anchor takes over from the next fix.
                val raw = if (lastSpeedMps < PARK_SPEED_MPS) 0f
                          else (v - lastSpeedMps) / dt
                // Time-constant form, so the filter behaves the same whether this receiver reports
                // at 1 Hz (as the Mi 9T does) or at 5 Hz. MAX_GPS_DT has already rejected the gaps
                // that would make dt meaningless here.
                gGps += (dt / (GPS_ACC_TAU + dt)).coerceIn(0f, 1f) * (raw / G - gGps)
                learnForwardAxis(raw)
            }
            lastSpeedMps = v
            speedMps = v
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /**
     * Learn which way is forward, using the one source that knows: the GPS.
     *
     * Over the interval the GPS just measured, we also have the average horizontal acceleration the
     * handset felt. Weighting that vector by the signed GPS acceleration and summing makes the
     * longitudinal component reinforce itself, while cornering force - orthogonal to it, and about
     * as often one way as the other - averages away instead of having to be filtered. The ratio of
     * the sum's length to the total evidence poured into it is how consistent the answer has been,
     * which is exactly the confidence the fusion needs.
     */
    private fun learnForwardAxis(gpsAcc: Float) {
        val n = aHCount
        if (n <= 0) return
        val ax = aHSum[0] / n; val ay = aHSum[1] / n; val az = aHSum[2] / n
        aHSum[0] = 0f; aHSum[1] = 0f; aHSum[2] = 0f; aHCount = 0

        // Slow decay, so a phone that was remounted (or shifted in a pocket) is relearned rather
        // than being outvoted forever by history.
        fwdAccum[0] *= FWD_FORGET; fwdAccum[1] *= FWD_FORGET; fwdAccum[2] *= FWD_FORGET
        fwdEnergy *= FWD_FORGET

        if (abs(gpsAcc) >= FWD_LEARN_MIN_ACC) {
            fwdAccum[0] += ax * gpsAcc
            fwdAccum[1] += ay * gpsAcc
            fwdAccum[2] += az * gpsAcc
            fwdEnergy += sqrt(ax * ax + ay * ay + az * az) * abs(gpsAcc)
        }
        updateConfidence()
    }

    private fun updateConfidence() {
        val len = sqrt(fwdAccum[0] * fwdAccum[0] + fwdAccum[1] * fwdAccum[1] + fwdAccum[2] * fwdAccum[2])
        if (len < 1e-6f || fwdEnergy < FWD_MIN_ENERGY) {
            fwdConfidence = 0f
            return
        }
        fwdRef[0] = fwdAccum[0] / len
        fwdRef[1] = fwdAccum[1] / len
        fwdRef[2] = fwdAccum[2] / len
        fwdConfidence = (len / fwdEnergy).coerceIn(0f, 1f)
    }
}
