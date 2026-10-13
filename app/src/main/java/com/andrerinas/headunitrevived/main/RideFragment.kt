package com.andrerinas.headunitrevived.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.main.ride.RideCardsController
import com.andrerinas.headunitrevived.main.ride.RideTelemetry
import com.andrerinas.headunitrevived.main.ride.ease
import com.andrerinas.headunitrevived.sensors.LeanEstimator
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.BluetoothHelper
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ride dashboard — a glanceable instrument cluster shown while Android Auto is not projecting.
 * SPEED (GPS), a true motorcycle LEAN gauge, and SUNSET (astronomical, from the GPS position).
 *
 * LEAN is produced by [LeanEstimator] — an app-scoped sensor-fusion engine (game-rotation-vector +
 * gyroscope + accelerometer + GPS) that keeps running while Android Auto projects, so the recorded
 * maxima accumulate across a ride. This fragment only observes that state and renders it; a
 * long-press on the gauge resets the recorded maxima (the mount reference is learned automatically).
 * SPEED comes from a local GPS listener (display-only).
 *
 * The third slot is a pager rather than a fixed card: it holds one of several
 * [com.andrerinas.headunitrevived.main.ride.RideCard]s that the rider swipes between, and the
 * choice is remembered across restarts. Everything about it lives in [RideCardsController]; this
 * fragment only feeds it locations and frames.
 *
 * Every dynamic value is critically-damped toward its target on a per-frame loop, so the readouts
 * glide instead of twitching regardless of sensor/GPS update rate.
 */
class RideFragment : Fragment() {

    private val commManager get() = App.provide(requireContext()).commManager

    // Views
    private lateinit var statusBar: StatusBarController
    private lateinit var leanGauge: LeanGaugeView
    private lateinit var tvSpeed: TextView
    private lateinit var tvLean: TextView
    private lateinit var tvMaxLeft: TextView
    private lateinit var tvMaxRight: TextView
    private lateinit var quickAndroidAuto: ImageView

    /** The pageable third slot (sunset, and whatever else lands there later). */
    private lateinit var rideCards: RideCardsController

    /** One reused instance — the frame loop runs at ~60 Hz, so this must not allocate. */
    private val telemetry = RideTelemetry()

    // Location (SPEED display + whatever the card pager needs)
    private var locationManager: LocationManager? = null

    // Targets fed by GPS / the lean estimator
    private var targetSpeed = 0f          // km/h
    private var targetLean = 0f           // deg, +right / −left (from LeanEstimator)

    // Displayed (eased) values
    private var dispSpeed = 0f
    private var dispLean = 0f
    private var maxLeft = 0
    private var maxRight = 0

    private var lastFrameNanos = 0L

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startLocation()
        }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startNativeAaConnection()
            else Toast.makeText(requireContext(), R.string.bt_permission_denied, Toast.LENGTH_LONG).show()
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_ride, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusBar = StatusBarController(view).also { it.attach() }
        leanGauge = view.findViewById(R.id.lean_gauge)
        tvSpeed = view.findViewById(R.id.tv_speed)
        tvLean = view.findViewById(R.id.tv_lean)
        tvMaxLeft = view.findViewById(R.id.tv_max_left)
        tvMaxRight = view.findViewById(R.id.tv_max_right)
        rideCards = RideCardsController(view, App.provide(requireContext()).settings).also { it.attach() }

        // Long-press the lean gauge: reset the recorded left/right maxima. (The mount reference is
        // learned automatically while riding straight — there is no manual calibration.)
        leanGauge.setOnLongClickListener {
            LeanEstimator.resetMax()
            maxLeft = 0; maxRight = 0
            leanGauge.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            Toast.makeText(requireContext(), R.string.ride_lean_reset, Toast.LENGTH_SHORT).show()
            true
        }

        // Centre the degree readout on the gauge's base line ("floor"), re-run on each layout.
        leanGauge.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> leanGauge.post { centerLeanReadoutOnFloor() } }

        view.findViewById<View>(R.id.quick_home).setOnClickListener { goHome() }
        quickAndroidAuto = view.findViewById(R.id.quick_android_auto)
        quickAndroidAuto.setOnClickListener { launchAndroidAuto() }
        view.findViewById<View>(R.id.quick_apps).setOnClickListener {
            val nav = findNavController()
            if (nav.currentDestination?.id == R.id.rideFragment) {
                nav.navigate(R.id.action_rideFragment_to_appsDrawerFragment)
            }
        }
        view.findViewById<View>(R.id.quick_settings).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        // Observe the lean estimator (it keeps running in the background; here we just mirror it).
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LeanEstimator.state.collect { s ->
                    targetLean = s.leanDeg
                    maxLeft = s.maxLeftDeg
                    maxRight = s.maxRightDeg
                }
            }
        }

        // Since v1.0.92 a session can come up while the rider stays here, with nothing on screen
        // to show for it. The quick-action button carries that state, so the answer sits on the
        // thing you would reach for anyway.
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commManager.connectionState.collect { renderAaButton() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AapService.scanningState.collect { renderAaButton() }
            }
        }
    }

    /**
     * Paints the Android Auto quick action with the connection state: gold when a session is live
     * (including one running headless), dimmed with an accent glyph while it is coming up, plain
     * otherwise. The dimmed-plus-accent language is the same one Home already uses while scanning.
     */
    private fun renderAaButton() {
        if (!::quickAndroidAuto.isInitialized) return
        val ctx = context ?: return
        val connecting = commManager.connectionState.value is CommManager.ConnectionState.Connecting ||
            AapService.scanningState.value

        val background: Int
        val icon: Int
        val alpha: Float
        when {
            commManager.isConnected -> {
                background = R.color.m3_primary
                icon = R.color.m3_on_primary
                alpha = 1.0f
            }
            connecting -> {
                background = R.color.m3_surface_container_high
                icon = R.color.m3_primary
                alpha = 0.6f
            }
            else -> {
                background = R.color.m3_surface_container_high
                icon = R.color.m3_on_surface
                alpha = 1.0f
            }
        }
        quickAndroidAuto.backgroundTintList = ContextCompat.getColorStateList(ctx, background)
        ImageViewCompat.setImageTintList(quickAndroidAuto, ContextCompat.getColorStateList(ctx, icon))
        quickAndroidAuto.alpha = alpha
    }

    override fun onResume() {
        super.onResume()
        statusBar.start()
        LeanEstimator.acquire("ride", requireContext())
        rideCards.start(requireContext())
        ensureLocation()
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        statusBar.stop()
        LeanEstimator.release("ride")
        rideCards.stop()
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        try { locationManager?.removeUpdates(coarseListener) } catch (_: Exception) {}
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /**
     * The cards outlive this view (they are plain objects held by the controller), and the theme
     * toggle recreates the activity, so the pager and every card reference has to be dropped here
     * or each toggle would leave one holding a dead view tree.
     */
    override fun onDestroyView() {
        rideCards.detach()
        super.onDestroyView()
    }

    // ===== Smoothing loop =====

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isResumed) return
            val dt = if (lastFrameNanos == 0L) 0.016f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
            lastFrameNanos = frameTimeNanos

            dispSpeed = ease(dispSpeed, targetSpeed, dt, TAU_SPEED)
            dispLean = ease(dispLean, targetLean, dt, TAU_LEAN)
            telemetry.frameDtSec = dt

            render()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun render() {
        tvSpeed.text = dispSpeed.roundToInt().coerceAtLeast(0).toString()

        leanGauge.lean = dispLean
        leanGauge.maxLeft = maxLeft
        leanGauge.maxRight = maxRight
        tvLean.text = "${abs(dispLean).roundToInt()}°"
        tvMaxLeft.text = "$maxLeft°"
        tvMaxRight.text = "$maxRight°"

        // Hand the same eased values to whichever card the rider has swiped to.
        telemetry.speedKmh = dispSpeed
        telemetry.leanDeg = dispLean
        telemetry.maxLeftDeg = maxLeft
        telemetry.maxRightDeg = maxRight
        telemetry.nowMillis = System.currentTimeMillis()
        rideCards.render(telemetry)
    }

    /**
     * Place the lean readout on the gauge's base line ("floor"), but clamp it so the whole number
     * stays inside the gauge box — sitting as low as it can without being clipped or crossing the arc.
     */
    private fun centerLeanReadoutOnFloor() {
        if (tvLean.height == 0 || leanGauge.height == 0) return
        val half = tvLean.height / 2f
        val margin = resources.displayMetrics.density * 6f
        // The readout and the gauge share the same top, so work in the gauge's own coordinates:
        // centre the number on the base line, clamped so it never leaves the gauge box.
        val center = leanGauge.baseLineY().coerceAtMost(leanGauge.height - half - margin)
        tvLean.translationY = center - half   // default centre is at 'half' (gravity top)
    }

    // ===== GPS: speed + card positions =====

    // Explicit implementation (not a SAM lambda): LocationListener's extra methods are only default
    // from API 30, so on the github flavour's minSdk 21 all four must be provided.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /**
     * Coarse position for the sunset card only. GPS needs satellites and cannot lock indoors, which
     * left the card sitting on "waiting for location" whenever the bike was in a garage — even
     * though the system already had a network position. Tens of metres of error moves a sunset time
     * by seconds, so the coarse fix is plenty here.
     *
     * It deliberately never touches the speed readout: only a real GPS fix carries a speed worth
     * showing.
     */
    private val coarseListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onCoarseLocation(location)
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun ensureLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startLocation()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startLocation() {
        val lm = locationManager ?: return
        try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocation(it) }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250L, 0f, locationListener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            AppLog.w("RideFragment: location permission missing: ${e.message}")
        } catch (e: Exception) {
            AppLog.w("RideFragment: cannot start GPS: ${e.message}")
        }
        startCoarseLocation(lm)
    }

    /**
     * Seed the sunset card from whatever coarse position is already cached, then keep a slow
     * listener for the case where nothing was cached yet. Providers are tried best-first and each
     * one may be missing on a given device, hence the per-provider try/catch.
     */
    private fun startCoarseLocation(lm: LocationManager) {
        var cached: Location? = null
        for (p in COARSE_PROVIDERS) {
            try {
                cached = lm.getLastKnownLocation(p)
                if (cached != null) break
            } catch (_: Exception) {}
        }
        // After a reboot with no internet the system's coarse cache stays empty for ~10 minutes
        // (measured), so the last GPS fix we stored ourselves is the only position there is. The
        // bike has not moved while parked; whichever of the two is newer wins, and a real GPS fix
        // still overrides both through the controller's latch.
        val persisted = App.provide(requireContext()).settings.lastGpsFix
        val seed = when {
            cached == null -> persisted
            persisted == null -> cached
            persisted.time > cached.time -> persisted
            else -> cached
        }
        AppLog.i("RideFragment: sunset seed from " + when {
            seed == null -> "nothing (no cached or stored position)"
            seed === persisted -> "the stored last GPS fix"
            else -> "the system coarse cache (" + seed.provider + ")"
        })
        seed?.let { onCoarseLocation(it) }
        for (p in COARSE_PROVIDERS) {
            try {
                lm.requestLocationUpdates(
                    p, COARSE_INTERVAL_MS, COARSE_DISTANCE_M, coarseListener, Looper.getMainLooper()
                )
                break
            } catch (_: Exception) {}
        }
    }

    private fun onLocation(loc: Location) {
        if (loc.hasSpeed()) targetSpeed = (loc.speed * 3.6f).coerceAtLeast(0f)
        telemetry.hasFix = true
        rideCards.onLocation(loc, precise = true)
    }

    /** A real GPS fix is always better; the controller holds the latch that enforces that. */
    private fun onCoarseLocation(loc: Location) {
        rideCards.onLocation(loc, precise = false)
    }

    // ===== Android Auto quick action (mirrors Home's AA tile) =====

    private fun launchAndroidAuto() {
        if (commManager.isConnected) {
            openProjection()
            return
        }
        val settings = App.provide(requireContext()).settings
        if (settings.wifiConnectionMode == 3) { // Native AA (the project default)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                startNativeAaConnection()
            }
        } else {
            // The richer connection UI for the other WiFi modes lives on Home — hand off there.
            goHome()
            Toast.makeText(requireContext(), R.string.android_auto, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openProjection() {
        startActivity(Intent(requireContext(), AapProjectionActivity::class.java)
            .putExtra(AapProjectionActivity.EXTRA_FOCUS, true))
    }

    /** Native-AA entry: poke the single remembered+paired device, else show the picker. */
    private fun startNativeAaConnection() {
        val known = getSingleKnownNativeAaDevice()
        if (known != null) pokeNativeAaDevice(known.address, known.name ?: "Unknown Device")
        else showNativeAaDeviceSelector()
    }

    private fun getSingleKnownNativeAaDevice(): android.bluetooth.BluetoothDevice? {
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext()) ?: return null
        if (!adapter.isEnabled) return null
        val remembered = App.provide(requireContext()).settings.autoStartBluetoothDeviceMacs
        if (remembered.isEmpty()) return null
        return try {
            val candidates = (adapter.bondedDevices ?: emptySet()).filter { it.address in remembered }
            if (candidates.size == 1) candidates.first() else null
        } catch (e: SecurityException) { null }
    }

    private fun pokeNativeAaDevice(mac: String, name: String) {
        AppLog.i("RideFragment: Native-AA poke to $name ($mac)")
        (requireActivity() as? MainActivity)?.beginAutoConnect(
            "ride android auto", MainActivity.ConnectionUiMode.OVERLAY)
        ContextCompat.startForegroundService(requireContext(),
            Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_NATIVE_AA_POKE
                putExtra(AapService.EXTRA_MAC, mac)
            })
    }

    private fun showNativeAaDeviceSelector() {
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(requireContext(), getString(R.string.bt_not_enabled), Toast.LENGTH_SHORT).show()
            return
        }
        val bonded = try { adapter.bondedDevices?.toList() ?: emptyList() } catch (e: SecurityException) { emptyList() }
        if (bonded.isEmpty()) {
            Toast.makeText(requireContext(), "No paired Bluetooth devices found", Toast.LENGTH_SHORT).show()
            return
        }
        val names = bonded.map { it.name ?: "Unknown Device" }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.select_bt_device)
            .setItems(names) { _, which ->
                val device = bonded[which]
                pokeNativeAaDevice(device.address, device.name ?: "Unknown Device")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun goHome() {
        val nav = findNavController()
        if (!nav.popBackStack(R.id.homeFragment, false)) nav.navigate(R.id.homeFragment)
    }

    companion object {
        private const val TAU_SPEED = 0.35f
        private const val TAU_LEAN = 0.22f

        /**
         * Coarse providers for the sunset card, best first. Written as literals rather than
         * `LocationManager.FUSED_PROVIDER` because that constant is API 31+ while the github
         * flavour still ships to minSdk 21 — the string has named the same provider all along.
         */
        private val COARSE_PROVIDERS = listOf("fused", LocationManager.NETWORK_PROVIDER)
        private const val COARSE_INTERVAL_MS = 60_000L
        private const val COARSE_DISTANCE_M = 500f
    }
}
