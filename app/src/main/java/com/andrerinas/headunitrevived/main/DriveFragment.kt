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
import com.andrerinas.headunitrevived.license.LicenseGuard
import com.andrerinas.headunitrevived.main.drive.HeadingArrowView
import com.andrerinas.headunitrevived.main.drive.DriveCards
import com.andrerinas.headunitrevived.main.ride.AltitudeReadout
import com.andrerinas.headunitrevived.main.ride.RideCardsController
import com.andrerinas.headunitrevived.main.ride.RideTelemetry
import com.andrerinas.headunitrevived.main.ride.ease
import com.andrerinas.headunitrevived.sensors.AltitudeTracker
import com.andrerinas.headunitrevived.sensors.DriveEstimator
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.BluetoothHelper
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Drive dashboard - the car counterpart of [RideFragment], opened from Home when Settings › General
 * › Vehicle type is Car. SPEED on the left (the same GPS readout), a pageable middle slot with
 * PITCH & ROLL and four-way G-FORCE, and ALTITUDE + HEADING on the right.
 *
 * All measuring lives in [DriveEstimator], held for as long as this screen is resumed (and by
 * Android Auto while it projects, see App). The pager is the Ride screen's [RideCardsController]
 * with its own cards and its own persisted choice; the altitude block is the Ride sunset card's
 * [AltitudeReadout]. The status bar and the quick-action row are the same as Ride's - the Android
 * Auto quick action is a deliberate copy of RideFragment's, kept there untouched rather than
 * refactored out from under a screen that is already field-proven.
 */
class DriveFragment : Fragment() {

    private val commManager get() = App.provide(requireContext()).commManager

    private lateinit var statusBar: StatusBarController
    private lateinit var tvSpeed: TextView
    private lateinit var tvHeading: TextView
    private lateinit var tvCardinal: TextView
    private lateinit var headingArrow: HeadingArrowView
    private lateinit var quickAndroidAuto: ImageView
    private lateinit var tvDemo: View
    private var shownDemo = false

    private lateinit var driveCards: RideCardsController
    private val altitude = AltitudeReadout()
    private val telemetry = RideTelemetry()

    private var locationManager: LocationManager? = null
    private var targetSpeed = 0f
    private var dispSpeed = 0f
    private var dispHeading = Float.NaN
    private var shownHeading = Int.MIN_VALUE
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
        inflater.inflate(R.layout.fragment_drive, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusBar = StatusBarController(view).also { it.attach() }
        tvSpeed = view.findViewById(R.id.tv_speed)
        tvHeading = view.findViewById(R.id.tv_heading)
        tvCardinal = view.findViewById(R.id.tv_heading_cardinal)
        headingArrow = view.findViewById(R.id.heading_arrow)
        tvDemo = view.findViewById(R.id.tv_drive_demo)
        altitude.bind(view.findViewById(R.id.tv_altitude), view.findViewById(R.id.altitude_spark))

        val settings = App.provide(requireContext()).settings
        driveCards = RideCardsController(
            view, settings, DriveCards.create(), settings::driveCardId, "DriveCards"
        ).also { it.attach() }

        view.findViewById<View>(R.id.quick_home).setOnClickListener { goHome() }
        quickAndroidAuto = view.findViewById(R.id.quick_android_auto)
        quickAndroidAuto.setOnClickListener { launchAndroidAuto() }
        view.findViewById<View>(R.id.quick_apps).setOnClickListener {
            val nav = findNavController()
            if (nav.currentDestination?.id == R.id.driveFragment) {
                nav.navigate(R.id.action_driveFragment_to_appsDrawerFragment)
            }
        }
        view.findViewById<View>(R.id.quick_settings).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as? LocationManager

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

    /** Same states and colours as RideFragment's quick action. */
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
                background = R.color.m3_primary; icon = R.color.m3_on_primary; alpha = 1.0f
            }
            connecting -> {
                background = R.color.m3_surface_container_high; icon = R.color.m3_primary; alpha = 0.6f
            }
            else -> {
                background = R.color.m3_surface_container_high; icon = R.color.m3_on_surface; alpha = 1.0f
            }
        }
        quickAndroidAuto.backgroundTintList = ContextCompat.getColorStateList(ctx, background)
        ImageViewCompat.setImageTintList(quickAndroidAuto, ContextCompat.getColorStateList(ctx, icon))
        quickAndroidAuto.alpha = alpha
    }

    override fun onResume() {
        super.onResume()
        if (!LicenseGuard.isUsable(requireContext())) {
            AppLog.w("LICENSE: Drive opened without a verified licence, leaving")
            findNavController().navigateUp()
            (activity as? MainActivity)?.checkSetupFlow()
            return
        }
        statusBar.start()
        DriveEstimator.acquire(OWNER, requireContext())
        driveCards.start(requireContext())
        ensureLocation()
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        statusBar.stop()
        DriveEstimator.release(OWNER)
        driveCards.stop()
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onDestroyView() {
        driveCards.detach()
        altitude.unbind()
        super.onDestroyView()
    }

    // ===== Smoothing loop =====

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isResumed) return
            val dt = if (lastFrameNanos == 0L) 0.016f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
            lastFrameNanos = frameTimeNanos
            dispSpeed = ease(dispSpeed, targetSpeed, dt, TAU_SPEED)
            telemetry.frameDtSec = dt
            render(dt)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun render(dt: Float) {
        // Demo (debug builds): the synthetic drive supplies the speed too, and the badge says so.
        val s = DriveEstimator.state.value
        if (s.demo != shownDemo) {
            shownDemo = s.demo
            tvDemo.visibility = if (s.demo) View.VISIBLE else View.GONE
            if (!s.demo) targetSpeed = 0f
        }
        if (s.demo) targetSpeed = s.demoSpeedKmh

        tvSpeed.text = dispSpeed.roundToInt().coerceAtLeast(0).toString()

        telemetry.speedKmh = dispSpeed
        telemetry.nowMillis = System.currentTimeMillis()
        driveCards.render(telemetry)
        altitude.render(dt, dispSpeed)
        renderHeading(dt)
    }

    /** Eased along the shorter way round, so 359° -> 1° turns two degrees, not 358. */
    private fun renderHeading(dt: Float) {
        val h = DriveEstimator.state.value.headingDeg
        if (h.isNaN()) {
            if (shownHeading != NO_HEADING) {
                shownHeading = NO_HEADING
                headingArrow.hasHeading = false
                tvHeading.text = getString(R.string.ride_no_signal)
                tvCardinal.text = ""
            }
            return
        }
        dispHeading = if (dispHeading.isNaN()) h else {
            var d = (h - dispHeading) % 360f
            if (d > 180f) d -= 360f else if (d < -180f) d += 360f
            val next = dispHeading + d * (1f - kotlin.math.exp(-dt / TAU_HEADING))
            ((next % 360f) + 360f) % 360f
        }
        headingArrow.hasHeading = true
        headingArrow.heading = dispHeading
        val deg = dispHeading.roundToInt() % 360
        if (deg != shownHeading) {
            shownHeading = deg
            tvHeading.text = "$deg°"
            val names = resources.getStringArray(R.array.drive_cardinals)
            val idx = (((deg + 22.5f) / 45f).toInt()) % 8
            tvCardinal.text = names.getOrNull(idx) ?: ""
        }
    }

    // ===== GPS: speed (and the altitude store, until DriveEstimator's own fixes arrive) =====

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
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
        // The car has not moved while parked: the stored last fix gives the altitude block a value
        // at once, as it does on the Ride screen. A real fix replaces it.
        if (AltitudeTracker.current.isNaN()) {
            App.provide(requireContext()).settings.lastGpsFix?.let { AltitudeTracker.onLocation(it) }
        }
        try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocation(it) }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250L, 0f, locationListener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            AppLog.w("DriveFragment: location permission missing: ${e.message}")
        } catch (e: Exception) {
            AppLog.w("DriveFragment: cannot start GPS: ${e.message}")
        }
    }

    private fun onLocation(loc: Location) {
        if (loc.hasSpeed() && !shownDemo) targetSpeed = (loc.speed * 3.6f).coerceAtLeast(0f)
        telemetry.hasFix = true
        AltitudeTracker.onLocation(loc)
        driveCards.onLocation(loc, precise = true)
    }

    // ===== Android Auto quick action (a copy of RideFragment's, see the class comment) =====

    private fun launchAndroidAuto() {
        if (commManager.isConnected) {
            startActivity(Intent(requireContext(), AapProjectionActivity::class.java)
                .putExtra(AapProjectionActivity.EXTRA_FOCUS, true))
            return
        }
        val settings = App.provide(requireContext()).settings
        if (settings.wifiConnectionMode == 3) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                startNativeAaConnection()
            }
        } else {
            goHome()
            Toast.makeText(requireContext(), R.string.android_auto, Toast.LENGTH_SHORT).show()
        }
    }

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
        AppLog.i("DriveFragment: Native-AA poke to $name ($mac)")
        (requireActivity() as? MainActivity)?.beginAutoConnect(
            "drive android auto", MainActivity.ConnectionUiMode.OVERLAY)
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
        private const val OWNER = "drive"
        private const val TAU_SPEED = 0.35f
        private const val TAU_HEADING = 0.30f
        private const val NO_HEADING = Int.MIN_VALUE + 1
    }
}
