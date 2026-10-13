package com.andrerinas.headunitrevived.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.graphics.Color
import android.content.res.ColorStateList
import android.widget.*
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintLayout
import android.net.VpnService
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.connection.NearbyManager
import com.andrerinas.headunitrevived.location.GpsFixMonitor
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import android.content.res.Configuration
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.AppThemeManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.VpnControl
import com.andrerinas.headunitrevived.utils.BluetoothHelper
import com.andrerinas.headunitrevived.connection.UsbReceiver
import com.andrerinas.headunitrevived.connection.UsbAccessoryMode
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private val commManager get() = App.provide(requireContext()).commManager

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            AppLog.i("VPN permission granted. Starting DummyVpnService and Self Mode.")
            VpnControl.startVpn(requireContext());
            startSelfModeInternal()
        } else {
            AppLog.w("VPN permission denied. Offline Self Mode might fail.")
            Toast.makeText(requireContext(), getString(R.string.failed_start_android_auto), Toast.LENGTH_LONG).show()
        }
    }

    // Set by the WiFi long-press before requesting BT permission so the grant callback forces
    // the picker instead of the normal auto-connect-to-remembered-device path.
    private var pendingForceSelector = false

    private val bluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            if (pendingForceSelector) showNativeAaDeviceSelector() else startNativeAaConnection()
        } else {
            Toast.makeText(requireContext(), R.string.bt_permission_denied, Toast.LENGTH_LONG).show()
        }
        pendingForceSelector = false
    }

    // Android Auto tile (formerly the Self Mode tile). Now a shortcut to the WiFi/Native-AA flow.
    private lateinit var self_mode_button: View
    private lateinit var rideButton: View
    private lateinit var settings: View
    private lateinit var appsButton: View
    private lateinit var exitButton: Button
    private lateinit var gpsIndicator: ImageView
    private var gpsPulse: GpsChipPulse? = null
    private lateinit var btIndicator: ImageView
    private lateinit var themeToggle: ImageView
    private lateinit var tvClock: TextView
    private lateinit var self_mode_label: TextView
    private lateinit var batteryView: BatteryView
    private lateinit var tvBattery: TextView

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) updateBattery(level * 100 / scale)
        }
    }

    // Status-bar clock + connection indicators refresh on a light 15s tick.
    private val clockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            refreshStatusIndicators()
            clockHandler.postDelayed(this, 15000)
        }
    }

    private var hasAttemptedAutoConnect = false
    private var hasAttemptedSingleUsbAutoConnect = false
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null

    // The scan progress feedback now rides on the Android Auto tile (it launches the WiFi flow).
    private fun updateWifiButtonFeedback(scanning: Boolean) {
        if (scanning) {
            self_mode_label.text = getString(R.string.searching)
            self_mode_button.alpha = 0.6f
        } else {
            self_mode_label.text = getString(R.string.android_auto)
            self_mode_button.alpha = 1.0f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        self_mode_button = view.findViewById(R.id.self_mode_button)
        rideButton = view.findViewById(R.id.ride_button)
        settings = view.findViewById(R.id.settings_button)
        appsButton = view.findViewById(R.id.apps_button)
        exitButton = view.findViewById(R.id.exit_button)
        self_mode_label = view.findViewById(R.id.self_mode_label)
        batteryView = view.findViewById(R.id.battery_view)
        tvBattery = view.findViewById(R.id.tv_battery)
        gpsIndicator = view.findViewById(R.id.gps_indicator)
        gpsPulse?.cancel()
        gpsPulse = GpsChipPulse(gpsIndicator)   // bound to this view; a recreated view gets a new one
        btIndicator = view.findViewById(R.id.bt_indicator)
        themeToggle = view.findViewById(R.id.theme_toggle)
        tvClock = view.findViewById(R.id.tv_clock)

        setupStatusBar()
        setupListeners()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AapService.scanningState.collect { updateWifiButtonFeedback(it) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GpsFixMonitor.state.collect { applyGpsIndicator(it) }
            }
        }

        val appSettings = App.provide(requireContext()).settings

        if (appSettings.autoStartOnScreenOn || appSettings.autoStartOnBoot) {
            ContextCompat.startForegroundService(requireContext(),
                Intent(requireContext(), AapService::class.java))
        }

        for (methodId in appSettings.autoConnectPriorityOrder) {
            if (commManager.isConnected) break
            when (methodId) {
                Settings.AUTO_CONNECT_LAST_SESSION -> {
                    if (appSettings.autoConnectLastSession && !hasAttemptedAutoConnect && !commManager.isConnected) {
                        hasAttemptedAutoConnect = true
                        if (attemptAutoConnect()) {
                            (requireActivity() as? MainActivity)?.beginAutoConnect(
                                "auto-connect last session",
                                MainActivity.ConnectionUiMode.PILL
                            )
                        }
                    }
                }
                Settings.AUTO_CONNECT_SELF_MODE -> {
                    if ((appSettings.autoStartSelfMode || forceSelfModeLaunch) && !hasAutoStarted && !commManager.isConnected) {
                        hasAutoStarted = true
                        forceSelfModeLaunch = false // Reset once processed
                        (requireActivity() as? MainActivity)?.beginAutoConnect(
                            "auto-start self mode",
                            MainActivity.ConnectionUiMode.PILL
                        )
                        startSelfMode()
                    }
                }
                Settings.AUTO_CONNECT_SINGLE_USB -> {
                    if (appSettings.autoConnectSingleUsbDevice && !hasAttemptedSingleUsbAutoConnect && !commManager.isConnected) {
                        hasAttemptedSingleUsbAutoConnect = true
                        if (attemptSingleUsbAutoConnect()) {
                            (requireActivity() as? MainActivity)?.beginAutoConnect(
                                "auto-connect single USB",
                                MainActivity.ConnectionUiMode.PILL
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startSelfModeInternal() {
        AapService.selfMode = true
        val intent = Intent(requireContext(), AapService::class.java)
        intent.action = AapService.ACTION_START_SELF_MODE
        ContextCompat.startForegroundService(requireContext(), intent)
        AppLog.i("Auto start selfmode")
    }

    private fun startSelfMode() {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork
        } else null

        if (activeNetwork == null && VpnControl.isVpnAvailable()) {
            AppLog.i("Device is offline. Preparing Dummy VPN for Self Mode.")
            val vpnIntent = VpnService.prepare(requireContext())
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
                return
            } else {
                AppLog.i("VPN permission already granted. Starting VPN service.")
                VpnControl.startVpn(requireContext());
            }
        } else if (activeNetwork == null) {
            AppLog.i("Device is offline and VPN is not available in this build. Self Mode may fail.")
        }
        startSelfModeInternal()
    }

    /**
     * Tries to start an auto-reconnect to the last session.
     *
     * @return `true` if a connection attempt was actually dispatched (so the
     *   caller should surface the pill), `false` if nothing was started (e.g.
     *   Native AA mode, no last session, missing USB device or permission).
     *   Returning a flag prevents the pill from being shown for 30 s when no
     *   work was queued.
     */
    private fun attemptAutoConnect(): Boolean {
        val ctx = context ?: return false
        val appSettings = App.provide(ctx).settings

        // [FIX] Skip manual WiFi connection if Native AA is selected.
        // Native AA handles its own handshake via Bluetooth/P2P.
        if (appSettings.wifiConnectionMode == 3) {
            AppLog.i("HomeFragment: Native AA mode active. Skipping manual auto-connect attempt.")
            return false
        }

        if (!appSettings.autoConnectLastSession ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) {
            return false
        }

        val connectionType = appSettings.lastConnectionType
        if (connectionType.isEmpty()) {
            AppLog.i("Auto-connect: No last session to reconnect to")
            return false
        }

        return when (connectionType) {
            Settings.CONNECTION_TYPE_WIFI -> {
                if (appSettings.wifiConnectionMode == 1) {
                    val ip = appSettings.lastConnectionIp
                    if (ip.isNotEmpty()) {
                        AppLog.i("Auto-connect: Attempting WiFi connection to $ip")
                        Toast.makeText(ctx, getString(R.string.auto_connecting_to, ip), Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch(Dispatchers.IO) { App.provide(ctx).commManager.connect(ip, 5277) }
                        ContextCompat.startForegroundService(ctx, Intent(ctx, AapService::class.java).apply {
                            action = AapService.ACTION_CONNECT_SOCKET
                        })
                        true
                    } else false
                } else {
                    AppLog.i("Auto-connect: Last session was WiFi, but connection mode is not Headunit Server. Skipping active connect.")
                    false
                }
            }
            Settings.CONNECTION_TYPE_USB -> {
                val lastUsbDevice = appSettings.lastConnectionUsbDevice
                if (lastUsbDevice.isNotEmpty()) {
                    val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
                    val matchingDevice = usbManager.deviceList.values.find { device ->
                        UsbDeviceCompat.getUniqueName(device) == lastUsbDevice
                    }
                    if (matchingDevice != null && usbManager.hasPermission(matchingDevice)) {
                        AppLog.i("Auto-connect: Attempting USB connection to $lastUsbDevice")
                        Toast.makeText(requireContext(), getString(R.string.auto_connecting_usb), Toast.LENGTH_SHORT).show()
                        ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_CHECK_USB
                        })
                        true
                    } else {
                        AppLog.i("Auto-connect: USB device $lastUsbDevice not found or no permission")
                        false
                    }
                } else false
            }
            Settings.CONNECTION_TYPE_NEARBY -> {
                AppLog.i("Auto-connect: Last session was via Google Nearby. AapService will handle discovery.")
                // No manual connect(ip) needed, NearbyManager in AapService manages this automatically on start.
                true
            }
            else -> false
        }
    }

    /**
     * @return `true` if a single-USB connection attempt was dispatched,
     *   `false` if guards (setting disabled, disclaimer pending, already
     *   connected) blocked it. Same intent as [attemptAutoConnect].
     */
    private fun attemptSingleUsbAutoConnect(): Boolean {
        val ctx = context ?: return false
        val appSettings = App.provide(ctx).settings
        if (!appSettings.autoConnectSingleUsbDevice ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) return false

        AppLog.i("HomeFragment: Requesting single-USB auto-connect via AapService")
        ContextCompat.startForegroundService(ctx,
            Intent(ctx, AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
            })
        return true
    }

    // ===== Material 3 status bar =====

    private fun setupStatusBar() {
        themeToggle.setOnClickListener { toggleTheme() }
        // Tap reports the fix state, long-press opens the system location settings. The chip
        // reads out rather than navigating because the state changes on its own (tunnels,
        // garages) and is the thing worth checking.
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
        refreshStatusIndicators()
        applyGpsIndicator(GpsFixMonitor.state.value)
    }

    /** Tap feedback for the GPS chip: haptic plus the current state, same shape as the lean reset. */
    private fun announceGpsFix() {
        val msg = when (GpsFixMonitor.state.value) {
            GpsFixMonitor.GpsFix.ACQUIRED -> R.string.gps_status_acquired
            GpsFixMonitor.GpsFix.SEARCHING -> R.string.sunset_waiting
            GpsFixMonitor.GpsFix.UNAVAILABLE -> R.string.gps_status_off
        }
        gpsIndicator.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun updateClock() {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        tvClock.text = fmt.format(java.util.Date())
    }

    /** Pill battery: the glyph is primary, switching to the error colour at ≤20%. */
    private fun updateBattery(percent: Int) {
        val pct = percent.coerceIn(0, 100)
        batteryView.setLevel(pct)
        tvBattery.text = "$pct%"
    }

    /** GPS chip lit when the GPS provider is on; BT chip lit when the adapter is on. */
    /** The Bluetooth chip only; the GPS chip is driven by [GpsFixMonitor] instead. */
    private fun refreshStatusIndicators() {
        val ctx = context ?: return
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
        val ctx = context ?: return
        btIndicator.visibility = if (btOn) View.GONE else View.VISIBLE
        if (btOn) return
        btIndicator.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.m3_surface_container))
        btIndicator.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.m3_error))
        btIndicator.contentDescription = getString(R.string.bt_not_enabled)
    }

    /**
     * Three states: a fix is the accent fill, searching is a gold glyph breathing on the plain chip
     * (see [GpsChipPulse]), and no provider (or no permission) turns the glyph red — that one is a
     * fault, not just an absence, because the phone's navigation runs on this GPS.
     */
    private fun applyGpsIndicator(fix: GpsFixMonitor.GpsFix) {
        val ctx = context ?: return
        val bg = if (fix == GpsFixMonitor.GpsFix.ACQUIRED) R.color.m3_primary else R.color.m3_surface_container
        val fg = when (fix) {
            GpsFixMonitor.GpsFix.ACQUIRED -> R.color.m3_on_primary
            GpsFixMonitor.GpsFix.SEARCHING -> R.color.m3_primary
            GpsFixMonitor.GpsFix.UNAVAILABLE -> R.color.m3_error
        }
        gpsIndicator.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, bg))
        gpsIndicator.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, fg))
        // Only breathe while the screen is actually showing; onResume restarts it.
        gpsPulse?.setSearching(fix == GpsFixMonitor.GpsFix.SEARCHING && isResumed)
    }

    private fun refreshThemeToggle() {
        val ctx = context ?: return
        val theme = App.provide(ctx).settings.appTheme
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
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
        val appSettings = App.provide(requireContext()).settings
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
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
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.w("HomeFragment: cannot open $action: ${e.message}")
        }
    }

    private fun setupListeners() {
        exitButton.setOnClickListener {
            val appSettings = App.provide(requireContext()).settings
            val keepServiceAlive = appSettings.autoStartOnBoot ||
                appSettings.autoStartOnScreenOn ||
                (appSettings.autoStartOnUsb && appSettings.reopenOnReconnection)
            if (keepServiceAlive) {
                val disconnectIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_DISCONNECT
                }
                ContextCompat.startForegroundService(requireContext(), disconnectIntent)
            } else {
                val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
            }
            requireActivity().finishAffinity()
        }

        // "Android Auto" tile (formerly Self Mode). Behaves like the old WiFi button:
        // if already connected, jump straight into the projection; otherwise launch the
        // connection flow for the configured WiFi mode. A long-press forces the device
        // picker in Native AA mode (same as the old WiFi long-press).
        self_mode_button.setOnClickListener {
            if (commManager.isConnected) {
                val aapIntent = Intent(requireContext(), AapProjectionActivity::class.java)
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                startActivity(aapIntent)
            } else {
                launchAndroidAutoConnection()
            }
        }

        self_mode_button.setOnLongClickListener {
            forceAndroidAutoDeviceSelector()
            true
        }

        // "Ride" tile — opens the instrument-cluster dashboard.
        rideButton.setOnClickListener {
            val controller = findNavController()
            if (controller.currentDestination?.id == R.id.homeFragment) {
                controller.navigate(R.id.action_homeFragment_to_rideFragment)
            }
        }

        settings.setOnClickListener {
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            startActivity(intent)
        }

        appsButton.setOnClickListener {
            val controller = findNavController()
            if (controller.currentDestination?.id == R.id.homeFragment) {
                controller.navigate(R.id.action_homeFragment_to_appsDrawerFragment)
            }
        }

    }

    /** Launches the connection flow for the configured WiFi mode (the old WiFi-button tap). */
    private fun launchAndroidAutoConnection() {
        val mode = App.provide(requireContext()).settings.wifiConnectionMode
        when (mode) {
                1 -> { // Auto (Headunit Server) - One-Shot Scan
                    if (commManager.isConnected) {
                        // Already connected
                    } else if (AapService.scanningState.value) {
                        Toast.makeText(requireContext(), getString(R.string.already_scanning), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.searching_headunit_server), Toast.LENGTH_SHORT).show()
                        (requireActivity() as? MainActivity)?.beginAutoConnect(
                            "manual WiFi headunit server scan",
                            MainActivity.ConnectionUiMode.OVERLAY
                        )
                        val intent = Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_START_WIRELESS_SCAN
                        }
                        ContextCompat.startForegroundService(requireContext(), intent)
                    }
                }
                2 -> { // Helper (Wireless Launcher)
                    if (commManager.isConnected) {
                        // Already connected
                    } else {
                        val strategy = App.provide(requireContext()).settings.helperConnectionStrategy
                        if (strategy == 4) {
                            if (!AapService.scanningState.value) {
                                (requireActivity() as? MainActivity)?.beginAutoConnect(
                                    "manual WiFi helper scan",
                                    MainActivity.ConnectionUiMode.OVERLAY
                                )
                                val intent = Intent(requireContext(), AapService::class.java).apply {
                                    action = AapService.ACTION_START_WIRELESS_SCAN
                                }
                                ContextCompat.startForegroundService(requireContext(), intent)
                            }
                            com.andrerinas.headunitrevived.utils.ShareHotspotQrDialog.show(
                                requireContext()
                            )
                        } else if (strategy == 2) {
                            // Nearby Devices — show live discovery dialog
                            showNearbyDeviceSelector()
                        } else if (AapService.scanningState.value) {
                            Toast.makeText(requireContext(), getString(R.string.already_searching_phone), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.searching_phone), Toast.LENGTH_SHORT).show()
                            (requireActivity() as? MainActivity)?.beginAutoConnect(
                                "manual WiFi helper scan",
                                MainActivity.ConnectionUiMode.OVERLAY
                            )
                            val intent = Intent(requireContext(), AapService::class.java).apply {
                                action = AapService.ACTION_START_WIRELESS_SCAN
                            }
                            ContextCompat.startForegroundService(requireContext(), intent)
                        }
                    }
                }
                3 -> { // Native AA
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        pendingForceSelector = false
                        bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        startNativeAaConnection()
                    }
                }
                else -> { // Manual (0) -> Open List
                    val controller = findNavController()
                    if (controller.currentDestination?.id == R.id.homeFragment) {
                        controller.navigate(R.id.action_homeFragment_to_networkListFragment)
                    }
                }
        }
    }

    /**
     * Force-opens the device/network picker (the old WiFi-button long-press). In Native AA mode
     * this lets the user pick a different phone even when one is already remembered (a normal tap
     * auto-connects to the remembered one). Other modes fall back to the manual network list.
     */
    private fun forceAndroidAutoDeviceSelector() {
        if (App.provide(requireContext()).settings.wifiConnectionMode == 3) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                pendingForceSelector = true
                bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                showNativeAaDeviceSelector()
            }
        } else {
            val controller = findNavController()
            if (controller.currentDestination?.id == R.id.homeFragment) {
                controller.navigate(R.id.action_homeFragment_to_networkListFragment)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.i("HomeFragment: onResume. isConnected=${commManager.isConnected}")
        refreshThemeToggle()
        updateClock()
        refreshStatusIndicators()
        val batteryIntent = requireContext().registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let { batteryReceiver.onReceive(requireContext(), it) }
        GpsFixMonitor.acquire("home", requireContext())
        // The flow does not re-emit on a resume that had no stop, so restart the pulse by hand.
        applyGpsIndicator(GpsFixMonitor.state.value)
        clockHandler.removeCallbacks(clockRunnable)
        clockHandler.postDelayed(clockRunnable, 15000)
        // In launcher mode the app IS the home screen, so there is nowhere to exit to —
        // hide the Exit button. Re-evaluated every resume so toggling the setting and
        // returning restores it in place (same listener/behavior).
        exitButton.visibility =
            if (App.provide(requireContext()).settings.useAsLauncher) View.GONE else View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        try { requireContext().unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        GpsFixMonitor.release("home")
        gpsPulse?.cancel()
        activeDialog?.dismiss()
        activeDialog = null
    }

    /**
     * Native AA entry point from a normal WiFi tap: if exactly one remembered auto-start device
     * is currently paired, connect straight to it (skipping the "Select Bluetooth Device" step);
     * otherwise fall back to the picker (first run, or several remembered devices). The remembered
     * list is filled automatically by [NativeAaHandshakeManager] after the first successful connect.
     */
    private fun startNativeAaConnection() {
        val known = getSingleKnownNativeAaDevice()
        if (known != null) {
            pokeNativeAaDevice(known.address, known.name ?: "Unknown Device")
        } else {
            showNativeAaDeviceSelector()
        }
    }

    /** The single remembered + currently-paired Native-AA device, or null if none or several. */
    private fun getSingleKnownNativeAaDevice(): android.bluetooth.BluetoothDevice? {
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext()) ?: return null
        if (!adapter.isEnabled) return null
        val remembered = App.provide(requireContext()).settings.autoStartBluetoothDeviceMacs
        if (remembered.isEmpty()) return null
        return try {
            val candidates = (adapter.bondedDevices ?: emptySet()).filter { it.address in remembered }
            if (candidates.size == 1) candidates.first() else null
        } catch (e: SecurityException) {
            null
        }
    }

    /** Fire the Native-AA poke for a specific device without showing the picker. */
    private fun pokeNativeAaDevice(mac: String, name: String) {
        AppLog.i("HomeFragment: Auto Native-AA poke to remembered device $name ($mac)")
        (requireActivity() as? MainActivity)?.beginAutoConnect(
            "auto Native-AA poke",
            MainActivity.ConnectionUiMode.OVERLAY
        )
        val intent = Intent(requireContext(), AapService::class.java).apply {
            action = AapService.ACTION_NATIVE_AA_POKE
            putExtra(AapService.EXTRA_MAC, mac)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun showNativeAaDeviceSelector() {
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())

        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(requireContext(), getString(R.string.bt_not_enabled), Toast.LENGTH_SHORT).show()
            return
        }

        val bondedDevices = adapter.bondedDevices?.toList() ?: emptyList()
        if (bondedDevices.isEmpty()) {
            Toast.makeText(requireContext(), "No paired Bluetooth devices found", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceNames = bondedDevices.map { it.name ?: "Unknown Device" }.toTypedArray()


        activeDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.select_bt_device)
            .setItems(deviceNames) { _, which ->
                val device = bondedDevices[which]
                AppLog.i("HomeFragment: Manually selected ${device.name} for Native-AA poke")

                (requireActivity() as? MainActivity)?.beginAutoConnect(
                    "manual Native-AA poke",
                    MainActivity.ConnectionUiMode.OVERLAY
                )
                val intent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_NATIVE_AA_POKE
                    putExtra(AapService.EXTRA_MAC, device.address)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
                Toast.makeText(requireContext(), "Searching for ${device.name}...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNearbyDeviceSelector() {
        // Ensure NearbyManager discovery is running via AapService
        ContextCompat.startForegroundService(requireContext(),
            Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_START_WIRELESS_SCAN
            })

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_nearby_selection, null)
        val deviceListView = dialogView.findViewById<ListView>(R.id.deviceList)
        val searchingText = dialogView.findViewById<TextView>(R.id.searchingText)
        val connectionProgress = dialogView.findViewById<ProgressBar>(R.id.connectionProgress)

        // Ensure the loading spinner is visible in both Light and Dark modes by forcing our brand color.
        val brandTeal = ContextCompat.getColor(requireContext(), R.color.brand_accent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            connectionProgress.indeterminateTintList = ColorStateList.valueOf(brandTeal)
            connectionProgress.indeterminateTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        } else {
            @Suppress("DEPRECATION")
            connectionProgress.indeterminateDrawable?.setColorFilter(brandTeal, android.graphics.PorterDuff.Mode.SRC_IN)
        }

        // Custom adapter to handle rounded backgrounds like in USB/Network lists
        val listAdapter = object : ArrayAdapter<NearbyManager.DiscoveredEndpoint>(requireContext(), R.layout.list_item_nearby) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_nearby, parent, false)
                val endpoint = getItem(position)
                view.findViewById<TextView>(R.id.deviceName).text = endpoint?.name ?: "Unknown"

                // Apply rounded backgrounds based on position
                val isTop = position == 0
                val isBottom = position == count - 1
                val bgRes = when {
                    isTop && isBottom -> R.drawable.bg_setting_single
                    isTop -> R.drawable.bg_setting_top
                    isBottom -> R.drawable.bg_setting_bottom
                    else -> R.drawable.bg_setting_middle
                }
                view.setBackgroundResource(bgRes)
                return view
            }
        }
        deviceListView.adapter = listAdapter

        var collectJob: Job? = null

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(getString(R.string.searching)) // Initial title
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                collectJob?.cancel()
                if (activeDialog == it) activeDialog = null
            }
            .create()

        activeDialog = dialog

        deviceListView.setOnItemClickListener { _, _, which, _ ->
            val endpoints = NearbyManager.discoveredEndpoints.value
            if (which < endpoints.size) {
                val endpoint = endpoints[which]
                AppLog.i("HomeFragment: Selected Nearby device: ${endpoint.name} (${endpoint.id})")

                // Hand off to the auto-connect overlay so the user gets the same
                // visual treatment as every other connection path. Pass the
                // endpoint name so the loading screen can show "Connecting to
                // <device>…" instead of the generic status text.
                val statusText = getString(R.string.connecting_to_nearby, endpoint.name)
                dialog.dismiss()
                (requireActivity() as? MainActivity)?.beginAutoConnect(
                    "manual Nearby select ${endpoint.name}",
                    MainActivity.ConnectionUiMode.OVERLAY,
                    statusText
                )

                val intent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_NEARBY_CONNECT
                    putExtra(AapService.EXTRA_ENDPOINT_ID, endpoint.id)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
            }
        }

        dialog.show()

        // Live-update the dialog list as endpoints are discovered
        collectJob = viewLifecycleOwner.lifecycleScope.launch {
            NearbyManager.discoveredEndpoints.collect { endpoints ->
                listAdapter.clear()
                listAdapter.addAll(endpoints)
                listAdapter.notifyDataSetChanged()

                if (endpoints.isEmpty()) {
                    dialog.setTitle(getString(R.string.searching))
                    searchingText.visibility = View.GONE
                } else {
                    dialog.setTitle(getString(R.string.nearby_device_found))
                    searchingText.visibility = View.VISIBLE
                    searchingText.text = getString(R.string.select_nearby_device) + " (${endpoints.size})"
                }
            }
        }
    }

    companion object {
        private var hasAutoStarted = false
        var forceSelfModeLaunch = false
        fun resetAutoStart() {
            hasAutoStarted = false
        }
    }
}
