package com.andrerinas.headunitrevived.sensors

import android.content.Context
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
import android.Manifest
import android.content.pm.PackageManager
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Estimates a motorcycle's **true lean angle** without ever trusting a gravity-tilt reading in a
 * corner. Runs as an application-scoped singleton so it keeps accumulating (e.g. max lean) while
 * Android Auto is projecting; screens [acquire]/[release] it and observe [state].
 *
 * ## Why gravity-tilt is avoided
 * In a steady corner the bike aligns with the *resultant* force (gravity + centripetal), so any
 * gravity-tilt source — a raw accelerometer, or the roll from a rotation vector — reads the bike as
 * ~upright and *under*-reads lean. So the lean is derived kinematically instead.
 *
 * ## This engine (kinematic-primary)
 *  - **No magnetometer.** Uses [Sensor.TYPE_GAME_ROTATION_VECTOR] (gyro+accel fusion, no compass) —
 *    a motorcycle's own magnetic field would corrupt a compass-based [Sensor.TYPE_ROTATION_VECTOR].
 *    The rotation matrix is used only to get *true vertical* in device coordinates.
 *  - **Kinematic lean drives the output:** φ = atan(v·ω_yaw / g), where ω_yaw is the turn rate about
 *    true vertical (gravity-referenced — hills and mount tilt don't leak in) and v is GPS speed. Field
 *    telemetry confirmed this tracks real corners cleanly; only the mount-orientation is irrelevant, so
 *    a wobbly mount barely matters. ω_yaw is aggressively low-passed to tame heavy engine vibration.
 *  - **Cornering gate rejects transients:** the filtered yaw must hold ONE sign, above a small
 *    threshold, for ~0.4 s before the angle is shown. A sustained corner passes; a lane-change /
 *    counter-steer reverses sign and resets the gate, so it never injects phantom lean. (The previous
 *    build gated on raw-vs-filtered yaw jitter, which motorcycle vibration kept permanently open-then-
 *    reset, so real corners were suppressed — this replaces that.)
 *  - EMA-smoothed, signed (+ = right / − = left) output at ~20 Hz (faster rise while cornering for a
 *    live feel); speed hysteresis (25/15 km/h) gates activation; a static detector forces 0 when parked.
 *
 * Safety note: a telemetry gauge, not a safety-critical instrument — a few degrees of error is fine.
 * The `LEAN_SIGN` / threshold constants below are field-tunable (flip a sign in one line if inverted).
 */
object LeanEstimator : SensorEventListener {

    data class LeanState(
        val leanDeg: Float = 0f,     // signed: + = right, − = left
        val maxLeftDeg: Int = 0,
        val maxRightDeg: Int = 0,
        val active: Boolean = false, // riding fast enough for a trustworthy reading
        val calibrated: Boolean = false // retained for API compatibility; always false (auto-learned)
    )

    private val _state = MutableStateFlow(LeanState())
    val state: StateFlow<LeanState> = _state

    // ----- tunables (field-adjustable) -----
    private const val G = 9.81f
    private const val ACT_HIGH_KMH = 25f       // activate above this speed
    private const val ACT_LOW_KMH = 15f        // deactivate below this speed (hysteresis)
    private const val PARK_SPEED_KMH = 2.5f
    private const val PARK_RATE_RAD = 0.06f    // ~3.4°/s: below this (roll+yaw) while stopped = park
    private const val PARK_TIME_MS = 1500L
    private const val GPS_STALE_MS = 2500L
    private const val MAX_ANGLE = 55f
    private const val EMIT_INTERVAL_MS = 50L   // ~20 Hz output

    private const val YAW_TAU = 0.40f          // aggressive low-pass on yaw-rate (transient rejection)
    private const val YAW_CORNER_MIN = 0.05f   // rad/s: below this (filtered) we're going straight
    private const val CORNER_TIME_S = 0.40f    // filtered yaw must hold one sign this long = real corner
    private const val OUT_TAU_CORNER = 0.30f   // output EMA time constant while cornering (live rise)
    private const val OUT_TAU_RELAX = 0.45f    // output EMA time constant otherwise (gentle fall to 0)
    private const val ACCEL_LP = 0.15f         // low-pass on the accelerometer (telemetry Accel_Z only)
    private const val MIN_KIN_SPEED = 4f       // m/s: below this the kinematic angle is meaningless
    private const val TELEMETRY_START_KMH = 5f // begin CSV logging once the bike is actually moving

    // Signs flip in one line if a field test shows the direction inverted for this mount:
    private const val LEAN_SIGN = -1f          // final output sign (left turn → negative)

    // ----- runtime -----
    private var appContext: Context? = null
    private val owners = HashSet<String>()
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null

    private val R = FloatArray(9)              // device→world (game rotation vector)
    private var haveR = false
    private val gyro = FloatArray(3)           // rad/s, device axes
    private var haveGyro = false
    private val accLP = FloatArray(3)          // low-passed accelerometer (telemetry Accel_Z only)
    private var haveAcc = false

    private var speedMps = 0f
    private var lastGpsMs = 0L

    private var yawRateF = 0f                   // low-passed yaw-rate about true vertical (rad/s)
    private var lastSign = 1f                   // last confident turn direction
    private var cornerTimer = 0f               // seconds the filtered yaw has held one sign (corner)
    private var lastYawSign = 0                 // sign of the filtered yaw during the current corner

    private var outDeg = 0f                     // EMA-smoothed output (deg)
    private var lastGyroNs = 0L
    private var lastEmitMs = 0L

    private var activeRiding = false
    private var parkedSince = 0L
    private var maxLeft = 0
    private var maxRight = 0

    private var telemetry: TelemetryLogger? = null
    private var telemetryArmed = false

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
            maxLeft = s.leanMaxLeft; maxRight = s.leanMaxRight
        } catch (_: Exception) {}

        // No magnetometer: GAME rotation vector = gyro+accel fusion only.
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }

        locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, locListener, Looper.getMainLooper())
            } catch (e: Exception) { AppLog.w("LeanEstimator: GPS start failed: ${e.message}") }
        }
        telemetry = TelemetryLogger(ctx)
        telemetryArmed = false

        running = true
        lastGyroNs = 0L
        AppLog.i("LeanEstimator started (game-rotation-vector, calibration-free)")
    }

    private fun stop() {
        sensorManager?.unregisterListener(this)
        try { locationManager?.removeUpdates(locListener) } catch (_: Exception) {}
        telemetry?.close(); telemetry = null; telemetryArmed = false
        running = false
        // Freeze the output but keep the recorded maxima for when a screen returns.
        _state.value = _state.value.copy(active = false)
        AppLog.i("LeanEstimator stopped")
    }

    /** Reset the recorded left/right maxima (long-press on the gauge). No calibration side-effect. */
    fun resetMax() {
        maxLeft = 0; maxRight = 0
        persistMax()
        emit(force = true)
    }

    private fun persistMax() {
        val ctx = appContext ?: return
        try {
            val s = App.provide(ctx).settings
            s.leanMaxLeft = maxLeft; s.leanMaxRight = maxRight
        } catch (_: Exception) {}
    }

    // ===== sensors =====

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(R, event.values)
                haveR = true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (!haveAcc) { System.arraycopy(event.values, 0, accLP, 0, 3); haveAcc = true }
                for (i in 0..2) accLP[i] += ACCEL_LP * (event.values[i] - accLP[i])
            }
            Sensor.TYPE_GYROSCOPE -> {
                System.arraycopy(event.values, 0, gyro, 0, 3); haveGyro = true
                step(event.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** One fusion step, driven by the (fast, steady) gyroscope stream. */
    private fun step(gyroNs: Long) {
        if (!haveR) return
        val dt = if (lastGyroNs == 0L) 0.02f else ((gyroNs - lastGyroNs) / 1e9f).coerceIn(0f, 0.1f)
        lastGyroNs = gyroNs

        // World-up expressed in device coordinates (gravity-referenced, magnetometer-free): row 2 of R.
        val up = floatArrayOf(R[6], R[7], R[8])
        normalize(up)

        // Yaw-rate about true vertical, then an aggressive low-pass (tames vibration + counter-steer).
        val yawRateRaw = dot(gyro, up)
        val yawA = (dt / (YAW_TAU + dt)).coerceIn(0f, 1f)
        yawRateF += yawA * (yawRateRaw - yawRateF)

        val speedKmh = speedMps * 3.6f
        val gpsFresh = SystemClock.elapsedRealtime() - lastGpsMs < GPS_STALE_MS

        // Turn direction (confident only when actually turning).
        if (abs(yawRateF) > 0.03f) lastSign = if (yawRateF >= 0f) 1f else -1f

        // --- Kinematic lean: φ = atan(v·ω_yaw / g), magnitude signed by turn direction. This is the
        // corner-correct estimate and (per field logs) the trustworthy one; it drives the output.
        val kinLean = if (gpsFresh && speedMps > MIN_KIN_SPEED)
            Math.toDegrees(atan((speedMps * abs(yawRateF) / G).toDouble())).toFloat() * lastSign
        else 0f

        // Cornering gate: the filtered yaw must hold ONE sign, above a small threshold, for a moment.
        // A sustained corner passes easily; a lane-change / counter-steer reverses sign and resets it,
        // so the kinematic angle is only shown for real, settled cornering (no vibration false-open).
        if (abs(yawRateF) > YAW_CORNER_MIN) {
            val s = if (yawRateF >= 0f) 1 else -1
            if (s == lastYawSign) cornerTimer += dt else cornerTimer = 0f
            lastYawSign = s
        } else cornerTimer = 0f
        val cornering = cornerTimer >= CORNER_TIME_S

        updateActivation(speedKmh, yawRateRaw)
        val now = SystemClock.elapsedRealtime()

        // --- Output: EMA toward the kinematic angle while cornering, else relax to 0. Faster rise for
        // a live feel, gentler fall so corner-exit eases back to upright. Forced to 0 when parked.
        val desired = if (activeRiding && !isParked(now) && gpsFresh && cornering) kinLean * LEAN_SIGN else 0f
        val tau = if (cornering) OUT_TAU_CORNER else OUT_TAU_RELAX
        outDeg += (dt / (tau + dt)).coerceIn(0f, 1f) * (desired - outDeg)
        outDeg = outDeg.coerceIn(-MAX_ANGLE, MAX_ANGLE)
        if (!activeRiding && abs(outDeg) < 0.3f) outDeg = 0f

        if (activeRiding) {
            var newMax = false
            if (outDeg > 0 && outDeg.roundToInt() > maxRight) { maxRight = outDeg.roundToInt(); newMax = true }
            if (outDeg < 0 && (-outDeg).roundToInt() > maxLeft) { maxLeft = (-outDeg).roundToInt(); newMax = true }
            if (newMax) persistMax()
        }

        if (now - lastEmitMs >= EMIT_INTERVAL_MS) {
            emit()
            val tel = telemetry
            if (tel != null) {
                if (!telemetryArmed && speedKmh > TELEMETRY_START_KMH) { tel.open(); telemetryArmed = true }
                if (telemetryArmed) tel.log(
                    System.currentTimeMillis(), speedKmh, yawRateRaw, yawRateF, accLP[2], outDeg,
                    kinLean, cornerTimer, if (cornering) 1 else 0, if (activeRiding) 1 else 0,
                    GForceEstimator.currentG, GForceEstimator.gpsG, GForceEstimator.confidence
                )
            }
        }
    }

    private fun updateActivation(speedKmh: Float, yawRateRaw: Float) {
        if (speedKmh >= ACT_HIGH_KMH) activeRiding = true
        else if (speedKmh < ACT_LOW_KMH) activeRiding = false
        // between the thresholds: hold the current activation state (hysteresis)

        val still = speedKmh < PARK_SPEED_KMH && abs(yawRateRaw) < PARK_RATE_RAD
        val now = SystemClock.elapsedRealtime()
        if (still) { if (parkedSince == 0L) parkedSince = now } else parkedSince = 0L
    }

    private fun isParked(now: Long): Boolean = parkedSince != 0L && now - parkedSince > PARK_TIME_MS

    private fun emit(force: Boolean = false) {
        lastEmitMs = SystemClock.elapsedRealtime()
        val s = LeanState(
            leanDeg = outDeg,
            maxLeftDeg = maxLeft,
            maxRightDeg = maxRight,
            active = activeRiding,
            calibrated = false
        )
        if (force || s != _state.value) _state.value = s
    }

    // ===== GPS =====

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            speedMps = if (location.hasSpeed()) location.speed else speedMps
            lastGpsMs = SystemClock.elapsedRealtime()
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // ===== small vector helpers =====

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun normalize(v: FloatArray) {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (n > 1e-6f) { v[0] /= n; v[1] /= n; v[2] /= n }
    }
}
