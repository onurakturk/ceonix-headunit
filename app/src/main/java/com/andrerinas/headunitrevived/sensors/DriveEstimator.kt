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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The car dashboard's measurements: four-way g, pitch & roll, and heading - all without knowing
 * how the phone is mounted. Application-scoped like [GForceEstimator], so Android Auto can hold it
 * through a projected drive and the maxima keep accumulating; screens [acquire]/[release] it.
 *
 * It deliberately borrows nothing from LeanEstimator (frozen by decision, and a car is not a bike:
 * a car body barely rolls, so the interesting angle is the road's, not the vehicle's lean).
 *
 * ## Two mount-independent quantities everything is built on
 *  - **Up** - the true vertical in device coordinates, from the game rotation vector (gyro-fused,
 *    smooth; magnetometer-free, so the car's metal does not disturb it).
 *  - **Yaw rate** - the gyroscope projected on that up vector: how fast the car is turning about
 *    the vertical, whichever way the handset faces. Left (counter-clockwise from above) is positive.
 *
 * ## Four-way g
 *  - **Longitudinal**: [GForceEstimator]'s GPS-anchored reading, used as it is (it is held here).
 *  - **Lateral**: speed x yaw rate, the centripetal acceleration of the path. No axis to learn and
 *    the sign comes from physics; the gyro is fast, so the receiver's ~1 Hz does not add lag (speed
 *    changes slowly). Valid while the tyres are not sliding, which is every road-legal corner.
 *
 * ## Pitch - the road grade, mount-independent
 *  Since 1.3.13 pitch never looks at the phone's angle: it is the altitude change over the last
 *  stretch of road divided by the distance (barometer where there is one, GPS altitude otherwise) -
 *  the car's equivalent of the Lean kinematic. A car S23 drive on 1.3.12 showed why: a mount that
 *  moves 3-5 deg read as +/-17 deg of pitch on a flat road. The price: no nose-dive under braking,
 *  and a parked car shows the last grade it drove.
 *
 * ## Roll - force isolation, averaged
 *  Camber has no mount-free measure, so roll is still read from the phone: the accelerometer minus
 *  what the car itself is doing (longitudinal g along the forward axis, the centripetal term
 *  sideways) leaves gravity alone - the "kinematic up" - and roll is that, averaged over [ROLL_TAU]
 *  so a shaking mount averages out. (The rotation-vector up, corrected toward the kinematic one over
 *  [ATT_TAU], still drives the slow level learning.)
 *
 *  Roll is measured against a **vehicle frame** in device coordinates: the level "up" (the mount
 *  offset) and the forward axis made perpendicular to it. The forward axis is learned here from
 *  pulls, stops *and* corners (a corner's known centripetal pull points left; left x up = forward),
 *  with GForceEstimator's as a fallback. The level up is learned automatically - a slow average of
 *  "up" over straight, steady stretches - and a long-press sets it on the spot ([setLevelHere]).
 *  Both are persisted, so a restart opens ready.
 *
 * ## Heading
 *  GPS course while moving is the absolute reference; between the ~1 Hz fixes the yaw rate is
 *  integrated so the compass turns smoothly, and each fix pulls it back halfway. Stopped, it holds.
 *
 * A telemetry gauge, not a safety instrument. Everything goes through a short output EMA (the
 * project's smoothness rule) and maxima accumulate only while actually moving.
 */
object DriveEstimator : SensorEventListener {

    data class DriveState(
        val longG: Float = 0f,          // + accelerating, - braking
        val latG: Float = 0f,           // + turning left (centripetal to the left), - turning right
        val maxAccG: Float = 0f,
        val maxBrakeG: Float = 0f,
        val maxLeftG: Float = 0f,
        val maxRightG: Float = 0f,
        val pitchDeg: Float = 0f,       // + nose up
        val rollDeg: Float = 0f,        // + right side down
        // Highest tilt reached per direction, positive degrees (Lean-style maxima).
        val maxPitchUpDeg: Float = 0f,
        val maxPitchDownDeg: Float = 0f,
        val maxRollLeftDeg: Float = 0f,
        val maxRollRightDeg: Float = 0f,
        val pitchReady: Boolean = false,    // one grade window has been travelled
        val rollReady: Boolean = false,     // a level reference and a forward axis are known
        val headingDeg: Float = Float.NaN,  // 0 = north, clockwise; NaN until a course was seen
        val active: Boolean = false,    // a fresh fix and the car is not standing still
        val demo: Boolean = false,      // synthetic values from [setDemo], not a measurement
        val demoSpeedKmh: Float = 0f    // the synthetic drive's speed, only meaningful with [demo]
    )

    private val _state = MutableStateFlow(DriveState())
    val state: StateFlow<DriveState> = _state

    // ----- tunables -----
    private const val G = 9.80665f
    private const val EMIT_INTERVAL_MS = 50L
    private const val GPS_STALE_MS = 3000L
    private const val PARK_SPEED_MPS = 0.8f     // same noise floor as GForceEstimator
    private const val PARK_TIME_MS = 1200L
    private const val MAX_PLAUSIBLE_G = 2.0f

    private const val YAW_TAU = 0.15f           // takes the vibration out of the yaw rate
    private const val OUT_TAU = 0.12f           // g output: the same feel as the Ride gauge
    private const val ATT_TAU = 2.0f            // crossover: kinematic up below, rotation vector above
    private const val BIAS_TAU = 5.0f           // gyro bias, learned only while standing still

    /** Level learning: only from straight, steady, moving stretches; seconds of such driving. */
    private const val LEVEL_TAU = 300f
    private const val LEVEL_MIN_SPEED = 4f      // m/s
    private const val LEVEL_MAX_YAW = 0.03f     // rad/s
    private const val LEVEL_MAX_LONG_G = 0.05f
    /** Sustained disagreement this large means the phone was remounted, not a road this steep. */
    private const val REMOUNT_DEG = 20f
    private const val REMOUNT_TIME_S = 20f

    private const val FWD_MIN_CONF = 0.5f       // GForceEstimator confidence before its axis is used

    // Pitch = road grade: altitude change over distance travelled. Mount-independent, like the Lean
    // kinematic - the phone's angle never enters it. 1.3.12's S23 drive showed why: with the angle
    // taken from the phone, a mount that moves 3-5 deg put +/-17 deg of "pitch" on a flat road.
    private const val GRADE_WINDOW_BARO = 40f   // m: a barometer resolves ~0.1-0.3 m, so 40 m is plenty
    private const val GRADE_WINDOW_GPS = 200f   // m: GPS altitude is +/-5 m, so it needs a long base
    private const val GRADE_SAMPLE_M = 5f       // one (distance, altitude) sample per 5 m travelled
    private const val GRADE_MIN_SPEED = 2.8f    // m/s (~10 km/h): below this, hold the last grade
    private const val PITCH_TAU = 1.5f          // grade output EMA
    private const val PRESSURE_TAU = 1.0f       // barometer low-pass

    // Roll stays an angle from the phone (camber has no mount-free measure), so it is averaged hard:
    // a mount's 3-5 deg of shake averages out, a real camber stays.
    private const val ROLL_TAU = 3.0f

    // Own forward-axis learning, from pulls/stops *and* corners, so the frame comes up in the first
    // few turns instead of after 3.5 km (the 1.3.12 S23 drive).
    private const val FRAME_LONG_MIN = 0.5f     // m/s^2 of GPS dv/dt before a pull/stop counts
    private const val FRAME_LAT_MIN = 1.0f      // m/s^2 (~0.1 g) of v*omega before a corner counts
    private const val FRAME_MEMORY_S = 600f     // evidence fades over ~10 min, so a remount is relearned
    private const val FRAME_MIN_ENERGY = 4f     // until this much evidence, confidence stays 0
    private const val FRAME_MIN_CONF = 0.35f
    private const val GRADE_SLOTS = 64         // 64 x 5 m covers the 200 m GPS window
    /** Tilt beyond the gauge's range is a transient (a remount, a jolt), not a road: not a maximum. */
    private const val MAX_PLAUSIBLE_DEG = 45f

    private const val HEADING_MIN_SPEED = 2.8f  // m/s (~10 km/h): below this GPS course is noise
    private const val HEADING_PULL = 0.5f       // share of the course error corrected per fix
    private const val PARKED_YAW_DEADBAND = 0.05f
    private const val PERSIST_INTERVAL_MS = 30_000L
    private const val TELEMETRY_START_KMH = 5f

    // ----- runtime -----
    private var appContext: Context? = null
    private val owners = HashSet<String>()
    private var running = false
    private val handler = Handler(Looper.getMainLooper())
    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null

    private val accel = FloatArray(3); private var haveAccel = false
    private val gyro = FloatArray(3)
    private val upFast = FloatArray(3); private var haveUp = false
    private var upFromRotationVector = false
    private val rot = FloatArray(9)

    private var yawRate = 0f                     // rad/s, bias-corrected, low-passed
    private var gyroBias = 0f

    private var speedMps = 0f
    private var lastGpsMs = 0L
    private var parkedSince = 0L
    private var activeDriving = false

    private var outLat = 0f
    private var outLong = 0f
    private var maxAcc = 0f; private var maxBrake = 0f
    private var maxLeft = 0f; private var maxRight = 0f
    private var maxPitchUp = 0f; private var maxPitchDown = 0f
    private var maxRollLeft = 0f; private var maxRollRight = 0f

    // Vehicle frame, device coordinates.
    private val levelUp = FloatArray(3); private var haveLevel = false
    private val fwdStored = FloatArray(3); private var haveFwdStored = false
    private val fwdTmp = FloatArray(3)
    private val fwd = FloatArray(3); private val right = FloatArray(3); private var haveFrame = false

    private val correction = FloatArray(3)       // slow offset: kinematic up - rotation-vector up
    private val upTrue = FloatArray(3)
    private val tmp = FloatArray(3)
    private var remountSeconds = 0f

    private var outPitch = 0f
    private var outRoll = 0f

    // Road grade.
    private var haveBaro = false
    private var baroP = Float.NaN                // hPa, low-passed
    private var gpsAlt = Float.NaN               // m, last GPS altitude
    private var distance = 0f                    // m travelled since start
    private val gradeS = FloatArray(GRADE_SLOTS)
    private val gradeH = FloatArray(GRADE_SLOTS)
    private var gradeCount = 0
    private var gradeHead = 0                    // next write index
    private var pitchRaw = 0f
    private var pitchReady = false

    // Own forward-axis evidence (device coordinates).
    private val frameAccum = FloatArray(3)
    private var frameEnergy = 0f
    private var frameConf = 0f
    private val frameFwd = FloatArray(3)
    private val aH = FloatArray(3)
    private val aHSum = FloatArray(3)
    private var aHCount = 0
    private var lastSpeedMps = 0f
    private var lastFixMs = 0L

    private var heading = Float.NaN              // degrees

    private var lastStepNs = 0L
    private var lastEmitMs = 0L
    private var lastPersistMs = 0L
    private var levelDirty = false

    private var telemetry: TelemetryLogger? = null
    private var telemetryArmed = false

    // Demo (debug builds): a synthetic drive replaces the measurement on screen. Nothing it shows
    // is persisted, learned or logged, so switching it off leaves the real state as it was.
    private var demo = false
    private var demoStartMs = 0L
    private val demoMax = FloatArray(4)          // acc, brake, left, right - this demo run only
    private val demoAttMax = FloatArray(4)       // nose up, nose down, roll left, roll right

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
        if (owners.isEmpty() && running) stop()
    }

    private fun start() {
        val ctx = appContext ?: return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sensorManager = sm

        // The longitudinal half of the four-way gauge, and the forward axis pitch/roll need.
        GForceEstimator.acquire(OWNER_GFORCE, ctx)

        try {
            val s = App.provide(ctx).settings
            maxAcc = s.driveMaxAcc / 1000f
            maxBrake = s.driveMaxBrake / 1000f
            maxLeft = s.driveMaxLeft / 1000f
            maxRight = s.driveMaxRight / 1000f
            maxPitchUp = s.driveMaxPitchUp / 10f
            maxPitchDown = s.driveMaxPitchDown / 10f
            maxRollLeft = s.driveMaxRollLeft / 10f
            maxRollRight = s.driveMaxRollRight / 10f
            loadFrame(s.driveLevelUp)
            heading = s.driveLastHeading
            demo = s.driveDemo && com.andrerinas.headunitrevived.BuildConfig.DEBUG
        } catch (_: Exception) {}

        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
        }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
        }
        // Up: the game rotation vector where there is one, the fused gravity sensor otherwise; on
        // hardware with neither, a slow low-pass of the accelerometer stands in (see onSensorChanged).
        val grv = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        upFromRotationVector = grv != null
        if (grv != null) {
            sm.registerListener(this, grv, SensorManager.SENSOR_DELAY_GAME, handler)
        } else {
            sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
            }
        }

        // Altitude for the road grade: the barometer where there is one (the S23), GPS otherwise.
        val baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        haveBaro = baro != null
        baro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }

        locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, locListener, Looper.getMainLooper())
            } catch (e: Exception) { AppLog.w("DriveEstimator: GPS start failed: " + e.message) }
        }

        telemetry = TelemetryLogger(ctx, TELEMETRY_PREFIX, TELEMETRY_HEADER)
        telemetryArmed = false
        haveUp = false
        haveAccel = false
        lastStepNs = 0L
        // The grade is measured per drive: a fresh window, nothing carried over.
        baroP = Float.NaN; gpsAlt = Float.NaN; distance = 0f
        gradeCount = 0; gradeHead = 0; pitchReady = false; outPitch = 0f
        aHSum.fill(0f); aHCount = 0; lastFixMs = 0L
        running = true
        AppLog.i("DriveEstimator started (rotation vector=" + upFromRotationVector +
            ", level=" + haveLevel + ", stored forward=" + haveFwdStored +
            ", altitude source=" + (if (haveBaro) "barometer" else "GPS") + ", demo=" + demo + ")")
        if (demo) startDemo()
    }

    private fun stop() {
        handler.removeCallbacks(demoTick)
        sensorManager?.unregisterListener(this)
        try { locationManager?.removeUpdates(locListener) } catch (_: Exception) {}
        GForceEstimator.release(OWNER_GFORCE)
        persistFrame(force = true)
        telemetry?.close(); telemetry = null; telemetryArmed = false
        running = false
        outLat = 0f; outLong = 0f
        _state.value = _state.value.copy(longG = 0f, latG = 0f, active = false)
        AppLog.i("DriveEstimator stopped")
    }

    /** Long-press on the g gauge. */
    fun resetMax() {
        maxAcc = 0f; maxBrake = 0f; maxLeft = 0f; maxRight = 0f
        demoMax.fill(0f)
        persistMax()
        emit(force = true)
    }

    /**
     * Long-press on the pitch & roll card: "the car is level right now". Takes the current up as
     * the level reference; automatic learning carries on from there.
     */
    fun setLevelHere() {
        if (!haveUp) return
        val src = if (haveAccel) upTrue else upFast
        copy(src, levelUp)
        haveLevel = true
        remountSeconds = 0f
        outRoll = 0f                     // pitch comes from the road grade and needs no level
        levelDirty = true
        persistFrame(force = true)
        AppLog.i("DriveEstimator: level set by the driver")
        emit(force = true)
    }

    /**
     * Pitch & roll maxima, Lean-style: the steepest grade each way and the hardest camber each
     * side, kept across restarts until the driver resets them. Fed only while driving, and only
     * within the gauge's range, so a remount or a jolt cannot plant an impossible record.
     */
    private fun trackAttitudeMax(pitch: Float, roll: Float, usePitch: Boolean, useRoll: Boolean) {
        var changed = false
        if (usePitch && abs(pitch) < MAX_PLAUSIBLE_DEG) {
            if (pitch > maxPitchUp + 0.1f) { maxPitchUp = pitch; changed = true }
            if (-pitch > maxPitchDown + 0.1f) { maxPitchDown = -pitch; changed = true }
        }
        if (useRoll && abs(roll) < MAX_PLAUSIBLE_DEG) {
            if (-roll > maxRollLeft + 0.1f) { maxRollLeft = -roll; changed = true }
            if (roll > maxRollRight + 0.1f) { maxRollRight = roll; changed = true }
        }
        if (changed) persistAttitudeMax()
    }

    /** Long-press menu on the pitch & roll page. Independent of [setLevelHere]. */
    fun resetAttitudeMax() {
        maxPitchUp = 0f; maxPitchDown = 0f; maxRollLeft = 0f; maxRollRight = 0f
        demoAttMax.fill(0f)
        persistAttitudeMax()
        AppLog.i("DriveEstimator: pitch & roll maxima reset by the driver")
        emit(force = true)
    }

    private fun persistAttitudeMax() {
        val ctx = appContext ?: return
        try {
            val s = App.provide(ctx).settings
            s.driveMaxPitchUp = (maxPitchUp * 10f).roundToInt()
            s.driveMaxPitchDown = (maxPitchDown * 10f).roundToInt()
            s.driveMaxRollLeft = (maxRollLeft * 10f).roundToInt()
            s.driveMaxRollRight = (maxRollRight * 10f).roundToInt()
        } catch (_: Exception) {}
    }

    private fun persistMax() {
        val ctx = appContext ?: return
        try {
            val s = App.provide(ctx).settings
            s.driveMaxAcc = (maxAcc * 1000f).roundToInt()
            s.driveMaxBrake = (maxBrake * 1000f).roundToInt()
            s.driveMaxLeft = (maxLeft * 1000f).roundToInt()
            s.driveMaxRight = (maxRight * 1000f).roundToInt()
        } catch (_: Exception) {}
    }

    // ===== sensors =====

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, gyro, 0, 3)
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                // World up expressed in device coordinates: the third row of device->world.
                upFast[0] = rot[6]; upFast[1] = rot[7]; upFast[2] = rot[8]
                haveUp = true
            }
            Sensor.TYPE_GRAVITY -> {
                if (normalizeInto(event.values, upFast)) haveUp = true
            }
            Sensor.TYPE_PRESSURE -> {
                val p = event.values[0]
                baroP = if (baroP.isNaN()) p else baroP + (0.2f / (PRESSURE_TAU + 0.2f)) * (p - baroP)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accel, 0, 3)
                haveAccel = true
                if (!upFromRotationVector && sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY) == null) {
                    // Neither composite sensor: a slow low-pass of the accelerometer is gravity.
                    if (!haveUp) { normalizeInto(accel, upFast); haveUp = true }
                    else {
                        normalizeInto(accel, tmp)
                        for (i in 0..2) upFast[i] += 0.02f * (tmp[i] - upFast[i])
                        normalizeInto(upFast, upFast)
                    }
                }
                step(event.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun step(eventNs: Long) {
        if (demo || !haveUp) return
        val dt = if (lastStepNs == 0L) 0.02f else ((eventNs - lastStepNs) / 1e9f).coerceIn(0f, 0.1f)
        lastStepNs = eventNs
        val now = SystemClock.elapsedRealtime()

        updateActivation(now)
        val gpsFresh = now - lastGpsMs < GPS_STALE_MS
        val parked = parkedSince != 0L && now - parkedSince > PARK_TIME_MS

        // --- yaw rate about the true vertical, bias-corrected ---
        val rawYaw = dot(gyro, upFast)
        val gyroQuiet = sqrt(dot(gyro, gyro)) < 0.05f
        if ((gpsFresh && parked) || (!gpsFresh && gyroQuiet)) {
            gyroBias += (dt / (BIAS_TAU + dt)) * (rawYaw - gyroBias)
        }
        yawRate += (dt / (YAW_TAU + dt)) * ((rawYaw - gyroBias) - yawRate)

        // --- four-way g ---
        val latRaw = if (gpsFresh && !parked) speedMps * yawRate / G else 0f
        val longRaw = GForceEstimator.currentG
        val latTarget = if (activeDriving) latRaw else 0f
        outLat += (dt / (OUT_TAU + dt)) * (latTarget - outLat)
        outLong = longRaw
        if (!activeDriving && abs(outLat) < 0.005f) outLat = 0f

        if (activeDriving && !parked) {
            var newMax = false
            if (abs(outLat) < MAX_PLAUSIBLE_G) {
                if (outLat > maxLeft) { maxLeft = outLat; newMax = true }
                if (-outLat > maxRight) { maxRight = -outLat; newMax = true }
            }
            if (abs(outLong) < MAX_PLAUSIBLE_G) {
                if (outLong > maxAcc) { maxAcc = outLong; newMax = true }
                if (-outLong > maxBrake) { maxBrake = -outLong; newMax = true }
            }
            if (newMax) persistMax()
        }

        // --- forward-axis evidence from corners (pulls and stops are added per GPS fix) ---
        if (haveAccel) {
            // Horizontal linear acceleration in device coordinates: strip what lies along "up".
            val along = dot(accel, upFast)
            for (i in 0..2) aH[i] = accel[i] - along * upFast[i]
            aHSum[0] += aH[0]; aHSum[1] += aH[1]; aHSum[2] += aH[2]; aHCount++
            val aLatMps2 = latRaw * G           // centripetal, + to the left
            if (gpsFresh && !parked && speedMps >= LEVEL_MIN_SPEED && abs(aLatMps2) >= FRAME_LAT_MIN) {
                // aH weighted by the known centripetal term reinforces "left"; left x up = forward.
                cross(aH, upFast, tmp)
                addFrameEvidence(tmp, aLatMps2 * dt, sqrt(dot(aH, aH)) * abs(aLatMps2) * dt)
            }
            decayFrameEvidence(dt)
        }

        // --- vehicle frame ---
        updateFrame()

        // --- roll (and the level learning it rests on) ---
        if (haveAccel) {
            // Kinematic up: the accelerometer minus what the car itself is doing. Without a frame
            // there is nothing to subtract along, and the rotation vector's up is the best we have.
            for (i in 0..2) tmp[i] = accel[i]
            if (haveFrame) {
                val aLong = outLong * G
                val aLat = latRaw * G           // centripetal, + to the left = -right
                for (i in 0..2) tmp[i] -= aLong * fwd[i] - aLat * right[i]
            }
            val haveKin = normalizeInto(tmp, tmp)
            if (haveKin) {
                val k = dt / (ATT_TAU + dt)
                for (i in 0..2) correction[i] += k * ((tmp[i] - upFast[i]) - correction[i])
            }
            for (i in 0..2) upTrue[i] = upFast[i] + correction[i]
            normalizeInto(upTrue, upTrue)

            learnLevel(dt, parked)

            // Roll straight from the kinematic up, averaged over ROLL_TAU - no gyro fast path, so
            // the mount shaking 3-5 deg averages out instead of being drawn the moment it happens.
            if (haveFrame && haveKin) {
                val roll = -Math.toDegrees(asin(dot(tmp, right).coerceIn(-1f, 1f).toDouble())).toFloat()
                outRoll += (dt / (ROLL_TAU + dt)) * (roll - outRoll)
            }
        }

        // --- pitch: road grade from altitude over distance ---
        if (gpsFresh) distance += speedMps * dt
        updateGrade(dt)

        if (activeDriving && !parked) trackAttitudeMax(outPitch, outRoll, pitchReady, haveFrame)

        // --- heading: integrate the turn between fixes ---
        if (!heading.isNaN() && (!parked || abs(yawRate) > PARKED_YAW_DEADBAND)) {
            heading = wrap360(heading - Math.toDegrees(yawRate.toDouble()).toFloat() * dt)
        }

        if (now - lastPersistMs > PERSIST_INTERVAL_MS) persistFrame(force = false)
        if (now - lastEmitMs >= EMIT_INTERVAL_MS) {
            logTelemetry(now, rawYaw, latRaw)
            emit()
        }
    }

    private fun updateActivation(now: Long) {
        activeDriving = now - lastGpsMs < GPS_STALE_MS
        if (speedMps < PARK_SPEED_MPS) {
            if (parkedSince == 0L) parkedSince = now
        } else {
            parkedSince = 0L
        }
    }

    /**
     * Road grade: the altitude change over the last [GRADE_WINDOW_BARO] / [GRADE_WINDOW_GPS] metres,
     * as an angle. One (distance, altitude) sample per [GRADE_SAMPLE_M] travelled; below
     * [GRADE_MIN_SPEED] nothing moves and the last grade is held. Ready once a whole window lies
     * behind the car.
     */
    private fun updateGrade(dt: Float) {
        val h = currentAltitude()
        if (h.isNaN()) return
        val lastS = if (gradeCount == 0) Float.NEGATIVE_INFINITY else gradeS[(gradeHead - 1 + GRADE_SLOTS) % GRADE_SLOTS]
        if (distance - lastS >= GRADE_SAMPLE_M) {
            gradeS[gradeHead] = distance
            gradeH[gradeHead] = h
            gradeHead = (gradeHead + 1) % GRADE_SLOTS
            if (gradeCount < GRADE_SLOTS) gradeCount++
        }
        if (speedMps < GRADE_MIN_SPEED || gradeCount < 2) return

        val window = if (haveBaro) GRADE_WINDOW_BARO else GRADE_WINDOW_GPS
        // Newest sample at least a window behind the current distance.
        var idx = -1
        for (k in 1..gradeCount) {
            val j = (gradeHead - k + GRADE_SLOTS) % GRADE_SLOTS
            if (distance - gradeS[j] >= window) { idx = j; break }
        }
        if (idx < 0) return
        val run = distance - gradeS[idx]
        if (run <= 0f) return
        pitchRaw = Math.toDegrees(kotlin.math.atan((h - gradeH[idx]) / run).toDouble()).toFloat()
        if (!pitchReady) {
            pitchReady = true
            outPitch = pitchRaw
            AppLog.i("DriveEstimator: pitch ready from the road grade (" + (if (haveBaro) "barometer" else "GPS altitude") + ")")
        }
        outPitch += (dt / (PITCH_TAU + dt)) * (pitchRaw - outPitch)
    }

    private fun currentAltitude(): Float =
        if (haveBaro) {
            if (baroP.isNaN()) Float.NaN
            else SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, baroP)
        } else gpsAlt

    private fun addFrameEvidence(v: FloatArray, weight: Float, energy: Float) {
        frameAccum[0] += v[0] * weight
        frameAccum[1] += v[1] * weight
        frameAccum[2] += v[2] * weight
        frameEnergy += energy
        val len = sqrt(dot(frameAccum, frameAccum))
        if (len < 1e-6f || frameEnergy < FRAME_MIN_ENERGY) { frameConf = 0f; return }
        for (i in 0..2) frameFwd[i] = frameAccum[i] / len
        frameConf = (len / frameEnergy).coerceIn(0f, 1f)
    }

    private fun decayFrameEvidence(dt: Float) {
        val f = 1f - dt / FRAME_MEMORY_S
        frameAccum[0] *= f; frameAccum[1] *= f; frameAccum[2] *= f
        frameEnergy *= f
    }

    /**
     * Level up and forward axis -> the frame roll is read in. Our own axis (pulls, stops and
     * corners) wins once it is confident; GForceEstimator's is the fallback while ours is still
     * weak; the stored one covers a fresh start.
     */
    private fun updateFrame() {
        if (!haveLevel) { haveFrame = false; return }
        val gConf = GForceEstimator.forwardAxis(fwdTmp)
        val src = when {
            frameConf >= FRAME_MIN_CONF -> { copy(frameFwd, fwdStored); haveFwdStored = true; levelDirty = true; frameFwd }
            gConf >= FWD_MIN_CONF -> { copy(fwdTmp, fwdStored); haveFwdStored = true; levelDirty = true; fwdTmp }
            haveFwdStored -> fwdStored
            else -> { haveFrame = false; return }
        }
        // Forward made perpendicular to the level up, right = forward x up.
        val along = dot(src, levelUp)
        for (i in 0..2) fwd[i] = src[i] - along * levelUp[i]
        if (!normalizeInto(fwd, fwd)) { haveFrame = false; return }
        right[0] = fwd[1] * levelUp[2] - fwd[2] * levelUp[1]
        right[1] = fwd[2] * levelUp[0] - fwd[0] * levelUp[2]
        right[2] = fwd[0] * levelUp[1] - fwd[1] * levelUp[0]
        haveFrame = normalizeInto(right, right)
    }

    /**
     * Learn the mount offset from driving: over straight, steady, moving stretches the road
     * averages out level, so the slow mean of "up" there is the car's level up. The first sample
     * seeds it (assume level until shown otherwise), and a sustained, large disagreement on such
     * a stretch is a remount - no road stays that steep for that long - so it reseeds.
     */
    private fun learnLevel(dt: Float, parked: Boolean) {
        if (!haveLevel) {
            copy(upTrue, levelUp)
            haveLevel = true
            levelDirty = true
            AppLog.i("DriveEstimator: level seeded from the current mount")
            return
        }
        val steady = !parked && speedMps >= LEVEL_MIN_SPEED && abs(yawRate) < LEVEL_MAX_YAW &&
            abs(outLong) < LEVEL_MAX_LONG_G && now() - lastGpsMs < GPS_STALE_MS
        if (!steady) return

        val angle = Math.toDegrees(acosSafe(dot(upTrue, levelUp)).toDouble()).toFloat()
        if (angle > REMOUNT_DEG) {
            remountSeconds += dt
            if (remountSeconds > REMOUNT_TIME_S) {
                copy(upTrue, levelUp)
                haveFwdStored = false          // the old forward belonged to the old mount
                frameAccum.fill(0f); frameEnergy = 0f; frameConf = 0f
                remountSeconds = 0f
                levelDirty = true
                AppLog.i("DriveEstimator: mount changed (" + angle.roundToInt() + " deg) - level reseeded")
            }
            return
        }
        remountSeconds = 0f
        val k = dt / (LEVEL_TAU + dt)
        for (i in 0..2) levelUp[i] += k * (upTrue[i] - levelUp[i])
        normalizeInto(levelUp, levelUp)
        levelDirty = true
    }

    private fun emit(force: Boolean = false) {
        lastEmitMs = SystemClock.elapsedRealtime()
        val s = DriveState(
            longG = outLong,
            latG = outLat,
            maxAccG = maxAcc,
            maxBrakeG = maxBrake,
            maxLeftG = maxLeft,
            maxRightG = maxRight,
            pitchDeg = outPitch,
            rollDeg = outRoll,
            maxPitchUpDeg = maxPitchUp,
            maxPitchDownDeg = maxPitchDown,
            maxRollLeftDeg = maxRollLeft,
            maxRollRightDeg = maxRollRight,
            pitchReady = pitchReady,
            rollReady = haveFrame,
            headingDeg = heading,
            active = activeDriving
        )
        if (force || s != _state.value) _state.value = s
    }

    private fun logTelemetry(now: Long, rawYaw: Float, latRaw: Float) {
        val tel = telemetry ?: return
        if (!telemetryArmed) {
            if (speedMps * 3.6f < TELEMETRY_START_KMH) return
            telemetryArmed = true
            tel.open()
        }
        val alt = currentAltitude()
        tel.logRow(now, floatArrayOf(
            speedMps * 3.6f, rawYaw, yawRate, gyroBias, latRaw, outLat, outLong,
            outPitch, outRoll, if (haveFrame) 1f else 0f,
            if (heading.isNaN()) -1f else heading, if (activeDriving) 1f else 0f,
            if (alt.isNaN()) -9999f else alt, distance, pitchRaw, if (haveBaro) 1f else 0f, frameConf
        ))
    }

    // ===== demo (debug builds) =====

    /** Developer › Debug › Drive demo. Takes effect at once if the engine is running. */
    fun setDemo(on: Boolean) {
        val enabled = on && com.andrerinas.headunitrevived.BuildConfig.DEBUG
        if (enabled == demo) return
        demo = enabled
        AppLog.i("DriveEstimator: demo " + if (enabled) "on" else "off")
        if (!running) return
        if (enabled) startDemo() else {
            handler.removeCallbacks(demoTick)
            lastStepNs = 0L
            emit(force = true)
        }
    }

    private fun startDemo() {
        demoStartMs = SystemClock.elapsedRealtime()
        demoMax.fill(0f)
        demoAttMax.fill(0f)
        handler.removeCallbacks(demoTick)
        handler.post(demoTick)
    }

    private val demoTick = object : Runnable {
        override fun run() {
            if (!running || !demo) return
            emitDemo()
            handler.postDelayed(this, EMIT_INTERVAL_MS)
        }
    }

    /**
     * A drive made of overlapping slow waves with no common period, so it never visibly repeats:
     * pulls and stops up to ~0.8 g (into the amber band), bends both ways up to ~0.75 g, grades of
     * up to ~12 deg of pitch and ~21 deg of roll (the design demo), a heading that keeps turning, speed between 30 and 100 km/h.
     */
    private fun emitDemo() {
        val t = (SystemClock.elapsedRealtime() - demoStartMs) / 1000.0
        fun w(period: Double, phase: Double = 0.0) = kotlin.math.sin(2 * Math.PI * t / period + phase).toFloat()
        val long = 0.45f * w(13.0) + 0.35f * w(31.0, 1.3)
        val lat = 0.55f * w(9.0, 1.0) * (0.65f + 0.35f * w(23.0))
        // The design handoff's own demo: up to ~12 deg of pitch and ~21 deg of roll, so both cross
        // into the warning colour now and then.
        val pitch = (9.0 * kotlin.math.sin(t * 0.45) + 3.0 * kotlin.math.sin(t * 1.3)).toFloat()
        val roll = (16.0 * kotlin.math.sin(t * 0.33 + 1.0) + 5.0 * kotlin.math.sin(t * 1.1)).toFloat()
        val headingDemo = ((t * 10.0 + 40.0 * kotlin.math.sin(2 * Math.PI * t / 19.0)) % 360.0).toFloat()
        val speed = (65f + 35f * w(27.0)).coerceAtLeast(0f)
        if (long > demoMax[0]) demoMax[0] = long
        if (-long > demoMax[1]) demoMax[1] = -long
        if (lat > demoMax[2]) demoMax[2] = lat
        if (-lat > demoMax[3]) demoMax[3] = -lat
        if (pitch > demoAttMax[0]) demoAttMax[0] = pitch
        if (-pitch > demoAttMax[1]) demoAttMax[1] = -pitch
        if (-roll > demoAttMax[2]) demoAttMax[2] = -roll
        if (roll > demoAttMax[3]) demoAttMax[3] = roll
        lastEmitMs = SystemClock.elapsedRealtime()
        _state.value = DriveState(
            longG = long, latG = lat,
            maxAccG = demoMax[0], maxBrakeG = demoMax[1], maxLeftG = demoMax[2], maxRightG = demoMax[3],
            pitchDeg = pitch, rollDeg = roll,
            maxPitchUpDeg = demoAttMax[0], maxPitchDownDeg = demoAttMax[1],
            maxRollLeftDeg = demoAttMax[2], maxRollRightDeg = demoAttMax[3],
            pitchReady = true, rollReady = true,
            headingDeg = if (headingDemo < 0f) headingDemo + 360f else headingDemo,
            active = true, demo = true, demoSpeedKmh = speed
        )
    }

    // ===== GPS =====

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = SystemClock.elapsedRealtime()
            lastGpsMs = now
            AltitudeTracker.onLocation(location)
            AltitudeTracker.altitudeOf(location)?.let { gpsAlt = it }
            if (location.hasSpeed()) {
                val v = location.speed
                // Forward-axis evidence from pulls and stops: the horizontal acceleration felt over
                // this GPS interval, weighted by the GPS's own dv/dt. The step out of a standstill
                // is skipped (the receiver holds 0 and then jumps), as in GForceEstimator.
                val dt = (now - lastFixMs) / 1000f
                if (lastFixMs != 0L && dt in 0.05f..2f && aHCount > 0 && lastSpeedMps >= PARK_SPEED_MPS) {
                    val acc = (v - lastSpeedMps) / dt
                    if (abs(acc) >= FRAME_LONG_MIN) {
                        val n = aHCount.toFloat()
                        tmp[0] = aHSum[0] / n; tmp[1] = aHSum[1] / n; tmp[2] = aHSum[2] / n
                        addFrameEvidence(tmp, acc * dt, sqrt(dot(tmp, tmp)) * abs(acc) * dt)
                    }
                }
                aHSum.fill(0f); aHCount = 0
                lastFixMs = now
                lastSpeedMps = v
                speedMps = v
            }
            if (location.hasBearing() && location.hasSpeed() && location.speed >= HEADING_MIN_SPEED) {
                val course = location.bearing
                heading = if (heading.isNaN()) course
                          else wrap360(heading + HEADING_PULL * wrap180(course - heading))
            }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // ===== persistence of the frame =====

    private fun persistFrame(force: Boolean) {
        lastPersistMs = SystemClock.elapsedRealtime()
        val ctx = appContext ?: return
        try {
            val s = App.provide(ctx).settings
            if (!heading.isNaN()) s.driveLastHeading = heading
            if (!haveLevel || !(levelDirty || force)) return
            levelDirty = false
            val parts = if (haveFwdStored)
                floatArrayOf(levelUp[0], levelUp[1], levelUp[2], fwdStored[0], fwdStored[1], fwdStored[2])
            else floatArrayOf(levelUp[0], levelUp[1], levelUp[2])
            s.driveLevelUp = parts.joinToString(",") { String.format(Locale.US, "%.5f", it) }
        } catch (_: Exception) {}
    }

    /** "ux,uy,uz" or "ux,uy,uz,fx,fy,fz". Anything malformed is ignored and relearned. */
    private fun loadFrame(stored: String) {
        haveLevel = false; haveFwdStored = false
        val v = stored.split(',').mapNotNull { it.trim().toFloatOrNull() }
        if (v.size >= 3) {
            for (i in 0..2) levelUp[i] = v[i]
            haveLevel = normalizeInto(levelUp, levelUp)
        }
        if (haveLevel && v.size >= 6) {
            for (i in 0..2) fwdStored[i] = v[3 + i]
            haveFwdStored = normalizeInto(fwdStored, fwdStored)
        }
    }

    // ===== small vector helpers (no allocation) =====

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun copy(src: FloatArray, dst: FloatArray) { dst[0] = src[0]; dst[1] = src[1]; dst[2] = src[2] }

    /** [out] = [a] x [b]; [out] must be neither input. */
    private fun cross(a: FloatArray, b: FloatArray, out: FloatArray) {
        out[0] = a[1] * b[2] - a[2] * b[1]
        out[1] = a[2] * b[0] - a[0] * b[2]
        out[2] = a[0] * b[1] - a[1] * b[0]
    }

    /** Writes the unit vector of [src] into [dst] (may be the same array); false when ~zero. */
    private fun normalizeInto(src: FloatArray, dst: FloatArray): Boolean {
        val n = sqrt(src[0] * src[0] + src[1] * src[1] + src[2] * src[2])
        if (n < 1e-6f) return false
        dst[0] = src[0] / n; dst[1] = src[1] / n; dst[2] = src[2] / n
        return true
    }

    private fun acosSafe(x: Float): Float = kotlin.math.acos(x.coerceIn(-1f, 1f))

    private fun wrap360(d: Float): Float { var r = d % 360f; if (r < 0f) r += 360f; return r }

    private fun wrap180(d: Float): Float { var r = wrap360(d); if (r > 180f) r -= 360f; return r }

    private fun now() = SystemClock.elapsedRealtime()

    private const val OWNER_GFORCE = "drive"
    private const val TELEMETRY_PREFIX = "DriveTelemetry_"
    private const val TELEMETRY_HEADER =
        "Time_ms,Speed_kmh,Raw_YawRate,YawRate,Gyro_Bias,Lat_G_Raw,Lat_G,Long_G,Pitch,Roll,Frame,Heading,Active,Alt_m,Dist_m,Grade_Pitch,Alt_Src,Fwd_Conf"
}
