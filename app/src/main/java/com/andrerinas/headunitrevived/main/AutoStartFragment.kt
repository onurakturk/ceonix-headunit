package com.andrerinas.headunitrevived.main

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.BluetoothHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AutoStartFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showBluetoothDeviceSelector()
        } else {
            showBluetoothPermissionDeniedDialog()
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            showBluetoothDeviceSelector()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_auto_start, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        toolbar = view.findViewById(R.id.toolbar)
        settingsAdapter = SettingsAdapter()
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = settingsAdapter

        updateSettingsList()
        setupToolbar()
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            handleBackPress()
        }
        // No Save item: each row writes straight through (see applyAutoStartChange).
    }

    private fun handleBackPress() {
        navigateBack()
    }

    /**
     * Called by a row right after it has written its value into [settings].
     *
     * Auto-start is read at boot and on screen-on by code that cannot touch the app's normal
     * preferences, so every flag is mirrored into device-protected storage. Re-mirroring a value
     * that did not change is harmless, so this simply pushes the whole set every time — which is
     * what the Save button did, without having to work out what changed.
     */
    private fun applyAutoStartChange() {
        val context = context ?: return

        Settings.syncAutoStartOnBootToDeviceStorage(context, settings.autoStartOnBoot)
        Settings.syncAutoStartOnScreenOnToDeviceStorage(context, settings.autoStartOnScreenOn)
        Settings.syncListenForUsbDevicesToDeviceStorage(context, settings.listenForUsbDevices)
        Settings.setUsbAttachedActivityEnabled(context, settings.listenForUsbDevices)
        Settings.syncAutoStartOnUsbToDeviceStorage(context, settings.autoStartOnUsb)
        Settings.syncAutoStartBtMacsToDeviceStorage(context, settings.autoStartBluetoothDeviceMacs)
        Settings.syncAutoStartOnWifiToDeviceStorage(context, settings.autoStartOnWifi)
        Settings.syncAutoStartWifiSsidToDeviceStorage(context, settings.autoStartWifiSsid)

        settings.autoStartBluetoothDeviceName = resolveBluetoothDeviceName()
        settings.commit()

        promptForOverlayPermissionIfNeeded()

        // Start the foreground service immediately when wake-detection settings are enabled,
        // so it can register the dynamic SCREEN_ON receiver.
        if (settings.autoStartOnScreenOn || settings.autoStartOnBoot) {
            ContextCompat.startForegroundService(context, Intent(context, AapService::class.java))
        }
    }

    /** The name is only shown on the row; the MAC set is what actually triggers auto-start. */
    private fun resolveBluetoothDeviceName(): String {
        val firstMac = settings.autoStartBluetoothDeviceMacs.firstOrNull() ?: return ""

        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
        val hasBtConnectPermission = if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        if (hasBtConnectPermission && adapter?.isEnabled == true) {
            try {
                val name = adapter.getRemoteDevice(firstMac).name
                if (!name.isNullOrEmpty()) return name
            } catch (e: Exception) {}
        }

        return if (settings.autoStartBluetoothDeviceMac == firstMac) {
            settings.autoStartBluetoothDeviceName
        } else {
            "Unknown Device"
        }
    }

    /** None of these can wake the projection without the overlay permission. */
    private fun promptForOverlayPermissionIfNeeded() {
        val anyEnabled = settings.autoStartBluetoothDeviceMacs.isNotEmpty() ||
            settings.autoStartOnUsb || settings.autoStartOnBoot ||
            settings.autoStartOnScreenOn || settings.autoStartOnWifi
        if (!anyEnabled || Build.VERSION.SDK_INT < 23) return
        if (android.provider.Settings.canDrawOverlays(requireContext())) return

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.overlay_permission_title)
            .setMessage(R.string.overlay_permission_description)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${requireContext().packageName}")
                ))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun navigateBack() {
        try {
            val navController = findNavController()
            if (!navController.navigateUp()) {
                requireActivity().finish()
            }
        } catch (e: Exception) {
            requireActivity().finish()
        }
    }

    private fun updateSettingsList() {
        val scrollState = recyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        items.add(SettingItem.CategoryHeader("autoStart", R.string.auto_start_settings))

        items.add(SettingItem.InfoBanner(
            stableId = "autoStartOemWarning",
            textResId = R.string.auto_start_oem_warning
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoStartOnBoot",
            nameResId = R.string.auto_start_on_boot_label,
            descriptionResId = R.string.auto_start_on_boot_description,
            isChecked = settings.autoStartOnBoot,
            onCheckedChanged = { isChecked ->
                settings.autoStartOnBoot = isChecked
                applyAutoStartChange()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoStartOnScreenOn",
            nameResId = R.string.auto_start_screen_on_label,
            descriptionResId = R.string.auto_start_screen_on_description,
            isChecked = settings.autoStartOnScreenOn,
            onCheckedChanged = { isChecked ->
                settings.autoStartOnScreenOn = isChecked
                applyAutoStartChange()
                updateSettingsList()
            }
        ))

        // USB auto-start rows (listenForUsbDevices / autoStartUsb / reopenOnReconnection) are not
        // shown: this is a wireless-only head unit. The settings and their handling stay in place,
        // so re-adding the rows here is all it takes to bring the feature back.

        items.add(SettingItem.SettingEntry(
            stableId = "autoStartBt",
            nameResId = R.string.auto_start_bt_label,
            value = getBluetoothSummaryText(),
            onClick = {
                showBluetoothDeviceSelector()
            }
        ))

        if (Build.VERSION.SDK_INT <= 32) {
            items.add(SettingItem.InfoBanner(
                stableId = "autoStartWifiWarning",
                textResId = R.string.auto_start_wifi_warning
            ))

            items.add(SettingItem.ToggleSettingEntry(
                stableId = "autoStartWifi",
                nameResId = R.string.auto_start_wifi_label,
                descriptionResId = R.string.auto_start_wifi_description,
                isChecked = settings.autoStartOnWifi,
                onCheckedChanged = { isChecked ->
                    settings.autoStartOnWifi = isChecked
                    applyAutoStartChange()
                    updateSettingsList()
                }
            ))

            if (settings.autoStartOnWifi == true) {
                items.add(SettingItem.SettingEntry(
                    stableId = "autoStartWifiSsid",
                    nameResId = R.string.auto_start_wifi_ssid_label,
                    value = if (settings.autoStartWifiSsid.isNullOrEmpty()) getString(R.string.wifi_ssid_not_set) else settings.autoStartWifiSsid,
                    onClick = {
                        showSsidInputDialog()
                    }
                ))
            }
        }

        settingsAdapter.submitList(items) {
            scrollState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check overlay permission. If the user returned from system settings
        // without granting it, disable auto-start settings that require it.
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(requireContext())) {
            var disabled = false
            if (settings.autoStartOnBoot) {
                settings.autoStartOnBoot = false
                settings.autoStartOnBoot = false
                disabled = true
            }
            if (settings.autoStartOnScreenOn) {
                settings.autoStartOnScreenOn = false
                Settings.syncAutoStartOnScreenOnToDeviceStorage(requireContext(), false)
                settings.autoStartOnScreenOn = false
                disabled = true
            }
            if (settings.autoStartOnUsb) {
                settings.autoStartOnUsb = false
                settings.autoStartOnUsb = false
                disabled = true
            }
            if (settings.autoStartBluetoothDeviceMacs.isNotEmpty()) {
                settings.autoStartBluetoothDeviceMacs = emptySet()
                settings.autoStartBluetoothDeviceName = ""
                Settings.syncAutoStartBtMacsToDeviceStorage(requireContext(), emptySet())
                disabled = true
            }
            if (settings.autoStartOnWifi) {
                settings.autoStartOnWifi = false
                settings.autoStartWifiSsid = ""
                Settings.syncAutoStartOnWifiToDeviceStorage(requireContext(), false)
                Settings.syncAutoStartWifiSsidToDeviceStorage(requireContext(), "")
                settings.autoStartOnWifi = false
                settings.autoStartWifiSsid = ""
                disabled = true
            }
            if (disabled) {
                AppLog.w("Overlay permission not granted, disabling auto-start settings")
                Toast.makeText(requireContext(), getString(R.string.overlay_permission_denied_auto_start_disabled), Toast.LENGTH_LONG).show()
                settings.commit()
                updateSettingsList()
            }
        }
    }

    private fun showBluetoothDeviceSelector() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            return
        }

        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())

        if (adapter == null || !adapter.isEnabled) {
            val enableIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(enableIntent)
            return
        }

        val bondedDevices = adapter.bondedDevices.toList()

        if (bondedDevices.isEmpty()) {
            Toast.makeText(requireContext(), "No paired Bluetooth devices found", Toast.LENGTH_LONG).show()
            return
        }

        val deviceNames = bondedDevices.map { device ->
            val hardwareName = device.name ?: "Unknown Device"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val alias = device.alias
                if (!alias.isNullOrEmpty() && alias != hardwareName) {
                    "$alias ($hardwareName)"
                } else {
                    alias ?: hardwareName
                }
            } else {
                hardwareName
            }
        }.toTypedArray()

        val checkedItems = bondedDevices.map { settings.autoStartBluetoothDeviceMacs.contains(it.address) }.toBooleanArray()
        val selectedMacs = settings.autoStartBluetoothDeviceMacs.toMutableSet()

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.select_bt_device)
            .setMultiChoiceItems(deviceNames, checkedItems) { _, which, isChecked ->
                val device = bondedDevices[which]
                if (isChecked) {
                    selectedMacs.add(device.address)
                } else {
                    selectedMacs.remove(device.address)
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.autoStartBluetoothDeviceMacs = selectedMacs
                applyAutoStartChange()
                updateSettingsList()
            }
            .setNeutralButton(R.string.remove) { _, _ ->
                settings.autoStartBluetoothDeviceMacs = emptySet()
                applyAutoStartChange()
                updateSettingsList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun getBluetoothSummaryText(): String {
        if (settings.autoStartBluetoothDeviceMacs.isEmpty()) {
            return getString(R.string.bt_device_not_set)
        }

        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
        val hasBtConnectPermission = if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        val bondedAddresses = if (adapter?.isEnabled == true && hasBtConnectPermission) {
            try {
                adapter.bondedDevices.map { it.address }.toSet()
            } catch (e: SecurityException) { null }
        } else null

        val validSelectedMacs = if (bondedAddresses != null) {
            settings.autoStartBluetoothDeviceMacs.filter { bondedAddresses.contains(it) }
        } else {
            settings.autoStartBluetoothDeviceMacs.toList()
        }

        if (validSelectedMacs.isEmpty()) {
            return getString(R.string.bt_device_not_set)
        }

        return if (validSelectedMacs.size == 1) {
            val mac = validSelectedMacs.first()
            if (hasBtConnectPermission && adapter?.isEnabled == true) {
                try {
                    val device = adapter.getRemoteDevice(mac)
                    val hardwareName = device.name ?: mac
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        device.alias ?: hardwareName
                    } else {
                        hardwareName
                    }
                } catch (e: Exception) {
                    mac
                }
            } else {
                if (settings.autoStartBluetoothDeviceMac == mac && settings.autoStartBluetoothDeviceName.isNotEmpty()) {
                    settings.autoStartBluetoothDeviceName
                } else {
                    mac
                }
            }
        } else {
            "${validSelectedMacs.size} ${getString(R.string.bt_devices_selected)}"
        }
    }

    private fun showBluetoothPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.bt_permission_denied_title)
            .setMessage(R.string.bt_permission_denied_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                val intent = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${requireContext().packageName}")
                )
                startActivity(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSsidInputDialog() {
        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(settings.autoStartWifiSsid)
        input.setSelection(input.text.length)

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.enter_wifi_ssid)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                settings.autoStartWifiSsid = input.text.toString()
                applyAutoStartChange()
                updateSettingsList()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        input.requestFocus()
    }
}
