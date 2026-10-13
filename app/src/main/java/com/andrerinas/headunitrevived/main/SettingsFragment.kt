package com.andrerinas.headunitrevived.main

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.main.permissions.PermissionItem
import com.andrerinas.headunitrevived.main.permissions.PermissionKind
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.utils.AppThemeManager
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.LocaleHelper
import com.andrerinas.headunitrevived.BuildConfig
import com.andrerinas.headunitrevived.utils.LogExporter
import com.andrerinas.headunitrevived.utils.SettingsBackupManager
import com.andrerinas.headunitrevived.utils.DialogUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import com.andrerinas.headunitrevived.connection.NativeAaHandshakeManager
import com.andrerinas.headunitrevived.utils.BluetoothHelper
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var settingsRecyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar

    // Which slice of the settings tree this instance renders. The screen is a hub:
    // SECTION_ROOT draws category cards, every other value draws that category's rows.
    // All sections share one nav destination (see action_settingsFragment_self), so
    // every sub-navigation action stays valid from any of them.
    // Set from the nav argument in onViewCreated.
    private var section: String = SECTION_ROOT

    // The Developer Settings sub-screen renders the advanced categories (Wireless, Automation,
    // Navigation, Graphic, Video, Input, Audio, Debug, Reset). Kept as a separate flag because
    // those blocks are long and unchanged.
    private var showDeveloperCategories: Boolean = false

    private var pendingStorageAction: (() -> Unit)? = null

    private val bluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            handleNativeAaSelection()
        } else {
            Toast.makeText(requireContext(), R.string.bt_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        val action = pendingStorageAction
        pendingStorageAction = null
        if (isGranted) {
            action?.invoke()
        } else {
            Toast.makeText(requireContext(), R.string.storage_permission_denied_backup, Toast.LENGTH_LONG).show()
        }
    }

    private val exportSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(SettingsBackupManager.MIME_TYPE)
    ) { uri ->
        uri?.let { exportSettingsToUri(it) }
    }

    private val importSettingsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importSettingsFromUri(it) }
    }

    private val legacyImportSettingsLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importSettingsFromUri(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        section = arguments?.getString(ARG_SECTION) ?: SECTION_ROOT
        showDeveloperCategories = section == SECTION_DEVELOPER

        settings = App.provide(requireContext()).settings


        // Intercept system back button
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        toolbar = view.findViewById(R.id.toolbar)
        settingsAdapter = SettingsAdapter()
        settingsRecyclerView = view.findViewById(R.id.settingsRecyclerView)
        settingsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        settingsRecyclerView.adapter = settingsAdapter

        updateSettingsList()
        setupToolbar()

        savedInstanceState?.getParcelable<android.os.Parcelable>("recycler_scroll")?.let {
            settingsRecyclerView.layoutManager?.onRestoreInstanceState(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::settingsRecyclerView.isInitialized) {
            settingsRecyclerView.layoutManager?.onSaveInstanceState()?.let {
                outState.putParcelable("recycler_scroll", it)
            }
        }
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            handleBackPress()
        }

        // Reset is now a single entry at the bottom of the Developer Settings screen,
        // so there is no toolbar reset button on any screen. Each section names itself.
        toolbar.title = getString(
            when (section) {
                SECTION_GENERAL -> R.string.category_general
                SECTION_DISPLAY -> R.string.category_display
                SECTION_AUDIO -> R.string.category_audio
                SECTION_BACKUP -> R.string.category_backup
                SECTION_INFO -> R.string.category_info
                SECTION_DEVELOPER -> R.string.developer_settings
                else -> R.string.settings
            }
        )

        // No Save item: every row writes straight through (see applyChange).
    }

    /**
     * Called by a row right after it has written its value into [settings].
     *
     * Flushes the write, and — for the settings the phone only reads while the session is being
     * negotiated — tears the session down so the new value is picked up, exactly as the old Save
     * button did. When Android Auto is not connected nothing happens and the change is silent.
     */
    private fun applyChange(needsSessionRestart: Boolean = false) {
        settings.commit()
        if (needsSessionRestart) requestSessionRestart()
    }

    private fun requestSessionRestart() {
        val context = context ?: return
        if (!App.provide(context).commManager.isConnected) return

        Toast.makeText(context, getString(R.string.settings_session_restart_notice), Toast.LENGTH_LONG).show()
        val stopServiceIntent = Intent(context, AapService::class.java).apply {
            action = AapService.ACTION_STOP_SERVICE
        }
        ContextCompat.startForegroundService(context, stopServiceIntent)
    }

    /**
     * Re-initialises the wireless stack after one of the three settings that describe it changed.
     * Same call the Save button used to make; [applyWirelessSideEffects] does it for imports.
     */
    private fun applyWirelessModeChange() {
        val context = context ?: return
        val intent = Intent(context, AapService::class.java).apply {
            val mode = settings.wifiConnectionMode
            action = if (mode == 1 || mode == 2 || mode == 3)
                AapService.ACTION_START_WIRELESS else AapService.ACTION_STOP_WIRELESS
        }
        context.startService(intent)
    }

    private fun handleBackPress() {
        navigateBack()
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
        val scrollState = settingsRecyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        when (section) {
            SECTION_ROOT -> addRootTiles(items)
            SECTION_GENERAL -> addGeneralItems(items)
            SECTION_DISPLAY -> addDisplayItems(items)
            SECTION_AUDIO -> addAudioItems(items)
            SECTION_BACKUP -> addBackupItems(items)
            SECTION_INFO -> addInfoItems(items)
        }

        addDeveloperItems(items)

        // No Save button at the end of the list: the toolbar already has one and it is always
        // reachable, whereas the list one sat below whatever the user had just scrolled past.

        settingsAdapter.submitList(items) {
            scrollState?.let { settingsRecyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    /**
     * The hub (S1): one card per category. Connection and Advanced have a single child each,
     * so their cards go straight to the target instead of through a one-row screen.
     */
    private fun addRootTiles(items: MutableList<SettingItem>) {
        items.add(SettingItem.NavTile(
            stableId = "tileGeneral",
            iconResId = R.drawable.ic_m3_general,
            titleResId = R.string.category_general,
            subtitleResId = R.string.settings_hub_general_desc,
            onClick = { openSection(SECTION_GENERAL) }
        ))

        items.add(SettingItem.NavTile(
            stableId = "tileDisplay",
            iconResId = R.drawable.ic_m3_display,
            titleResId = R.string.category_display,
            subtitleResId = R.string.settings_hub_display_desc,
            onClick = { openSection(SECTION_DISPLAY) }
        ))

        items.add(SettingItem.NavTile(
            stableId = "tileAudio",
            iconResId = R.drawable.ic_m3_audio,
            titleResId = R.string.category_audio,
            subtitleResId = R.string.settings_hub_audio_desc,
            onClick = { openSection(SECTION_AUDIO) }
        ))

        items.add(SettingItem.NavTile(
            stableId = "tileConnection",
            iconResId = R.drawable.ic_m3_wifi,
            titleResId = R.string.category_connection,
            subtitleResId = R.string.settings_hub_connection_desc,
            onClick = { navigateSafely(R.id.action_settingsFragment_to_bssidSetupFragment) }
        ))

        items.add(SettingItem.NavTile(
            stableId = "tileBackup",
            iconResId = R.drawable.ic_m3_backup,
            titleResId = R.string.category_backup,
            subtitleResId = R.string.settings_hub_backup_desc,
            onClick = { openSection(SECTION_BACKUP) }
        ))

        items.add(SettingItem.NavTile(
            stableId = "tileAdvanced",
            iconResId = R.drawable.ic_m3_advanced,
            titleResId = R.string.category_advanced,
            subtitleResId = R.string.settings_hub_advanced_desc,
            onClick = { openSection(SECTION_DEVELOPER) }
        ))

        items.add(SettingItem.NavTile(
            stableId = "tileInfo",
            iconResId = R.drawable.ic_m3_info,
            titleResId = R.string.category_info,
            subtitleResId = R.string.settings_hub_info_desc,
            onClick = { openSection(SECTION_INFO) }
        ))
    }

    /** Opens another slice of this same screen (same nav destination, different argument). */
    private fun openSection(target: String) {
        try {
            findNavController().navigate(
                R.id.action_settingsFragment_self,
                Bundle().apply { putString(ARG_SECTION, target) }
            )
        } catch (e: Exception) {
            AppLog.e("SettingsFragment: could not open section $target", e)
        }
    }

    private fun navigateSafely(actionId: Int) {
        try {
            findNavController().navigate(actionId)
        } catch (e: Exception) {
            AppLog.e("SettingsFragment: navigation failed", e)
        }
    }

    private fun addInfoItems(items: MutableList<SettingItem>) {
        items.add(SettingItem.SettingEntry(
            stableId = "version",
            nameResId = R.string.version,
            value = BuildConfig.VERSION_NAME,
            onClick = { /* Read only */ }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "licence",
            nameResId = R.string.open_source_licence,
            value = getString(R.string.open_source_licence_desc),
            onClick = { _ -> showLicenceNotice() }
        ))
    }

    /** The AGPL legal notice: whose work this is, that there is no warranty, where the source is. */
    private fun showLicenceNotice() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.open_source_licence)
            .setMessage(getString(R.string.open_source_licence_body, getString(R.string.source_code_url)))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun addBackupItems(items: MutableList<SettingItem>) {
        items.add(SettingItem.SettingEntry(
            stableId = "exportSettings",
            nameResId = R.string.export_settings,
            value = getString(R.string.export_settings_description),
            onClick = { _ -> startExportSettings() }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "importSettings",
            nameResId = R.string.import_settings,
            value = getString(R.string.import_settings_description),
            onClick = { _ -> startImportSettings() }
        ))
    }

    /** Display: how Android Auto and the app itself are drawn. */
    private fun addDisplayItems(items: MutableList<SettingItem>) {
        // Interface size — how large Android Auto draws its own controls. Picks the resolution and
        // density together; needs a reconnect because density only travels in the handshake.
        items.add(SettingItem.SettingEntry(
            stableId = "uiSize",
            nameResId = R.string.ui_size,
            value = getString(settings.uiSize.labelRes),
            onClick = { _ ->
                val labels = Settings.UiSize.values().map { getString(it.labelRes) }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.ui_size)
                    .setSingleChoiceItems(labels, settings.uiSize.id) { dialog, which ->
                        settings.uiSize = Settings.UiSize.fromId(which)
                        applyChange(needsSessionRestart = true)
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        ))

        // Read through the helpers: the translated arrays are shorter than the base one, so
        // indexing them by the enum value threw for anyone on a mode past their array's end.
        val darkModeValue = "${getString(R.string.app_theme_short)}: " +
                "${AppThemeManager.appThemeLabel(resources, settings.appTheme)} · " +
                "${getString(R.string.night_mode_short)}: " +
                AppThemeManager.nightModeLabel(resources, settings.nightMode)
        items.add(SettingItem.SettingEntry(
            stableId = "darkModeSettings",
            nameResId = R.string.dark_mode_settings,
            value = darkModeValue,
            onClick = { navigateSafely(R.id.action_settingsFragment_to_darkModeFragment) }
        ))

        // Screen mode — persists immediately (Rescue Mode) and recreates the activity.
        items.add(SettingItem.SettingEntry(
            stableId = "startInFullscreenMode",
            nameResId = R.string.start_in_fullscreen_mode,
            value = when (settings.fullscreenMode) {
                Settings.FullscreenMode.NONE -> getString(R.string.fullscreen_none)
                Settings.FullscreenMode.IMMERSIVE -> getString(R.string.fullscreen_immersive)
                Settings.FullscreenMode.STATUS_ONLY -> getString(R.string.fullscreen_status_only)
                Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH -> getString(R.string.fullscreen_immersive_avoid_notch)
                else -> getString(R.string.auto)
            },
            onClick = {
                val modes = arrayOf(
                    getString(R.string.fullscreen_none),
                    getString(R.string.fullscreen_immersive),
                    getString(R.string.fullscreen_status_only),
                    getString(R.string.fullscreen_immersive_avoid_notch)
                )
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.start_in_fullscreen_mode)
                    .setSingleChoiceItems(modes, settings.fullscreenMode.value) { dialog, which ->
                        val newMode = Settings.FullscreenMode.fromInt(which) ?: Settings.FullscreenMode.NONE
                        settings.fullscreenMode = newMode

                        // PERSIST IMMEDIATELY (Rescue Mode)
                        settings.fullscreenMode = newMode
                        settings.commit()

                        applyChange()
                        dialog.dismiss()
                        updateSettingsList()

                        // Apply immediately to current UI
                        requireActivity().recreate()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "stretchToFill",
            nameResId = R.string.pref_stretch_screen_title,
            descriptionResId = R.string.pref_stretch_screen_summary,
            isChecked = settings.stretchToFill,
            onCheckedChanged = { isChecked ->
                // Read live by the scaler on every layout pass, so no session restart is needed.
                settings.stretchToFill = isChecked
                applyChange()
                updateSettingsList()
            }
        ))
    }

    /** Audio: where Android Auto's sound comes out. The rest lives in Developer Settings. */
    private fun addAudioItems(items: MutableList<SettingItem>) {
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "enableAudioSink",
            nameResId = R.string.enable_audio_sink,
            descriptionResId = R.string.enable_audio_sink_description,
            isChecked = settings.enableAudioSink,
            onCheckedChanged = { isChecked ->
                settings.enableAudioSink = isChecked
                applyChange(needsSessionRestart = true)
                updateSettingsList()
            }
        ))

        if (settings.enableAudioSink == true) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "staticAudioFocus",
                nameResId = R.string.static_audio_focus,
                descriptionResId = R.string.static_audio_focus_description,
                isChecked = settings.staticAudioFocus,
                onCheckedChanged = { isChecked ->
                    settings.staticAudioFocus = isChecked
                    applyChange(needsSessionRestart = true)
                    updateSettingsList()
                }
            ))
        }
    }

    private fun addGeneralItems(items: MutableList<SettingItem>) {
        // First in the section, and its summary is live on purpose. The dashboard stops offering
        // itself once the rider dismisses it with Continue, so for recommended gaps this row is
        // the only place they stay visible.
        items.add(SettingItem.SettingEntry(
            stableId = "permissionDashboard",
            nameResId = R.string.permission_dashboard_title,
            value = getPermissionSummary(),
            onClick = { navigateSafely(R.id.action_settingsFragment_to_permissionDashboardFragment) }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "launcherModeSettings",
            nameResId = R.string.launcher_mode_settings,
            value = getString(R.string.launcher_mode_settings_summary),
            onClick = { navigateSafely(R.id.action_settingsFragment_to_launcherModeFragment) }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "autoStartSettings",
            nameResId = R.string.auto_start_settings,
            value = getString(R.string.auto_start_settings_description),
            onClick = { navigateSafely(R.id.action_settingsFragment_to_autoStartFragment) }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "autoConnectSettings",
            nameResId = R.string.auto_connect_settings,
            value = getAutoConnectSummary(),
            onClick = { navigateSafely(R.id.action_settingsFragment_to_autoConnectFragment) }
        ))

        // Language Selector
        val availableLocales = LocaleHelper.getAvailableLocales(requireContext())
        val currentLocale = LocaleHelper.stringToLocale(settings.appLanguage ?: "")
        val currentLanguageDisplay = if (currentLocale != null) {
            LocaleHelper.getDisplayName(currentLocale)
        } else {
            getString(R.string.system_default)
        }

        items.add(SettingItem.SettingEntry(
            stableId = "appLanguage",
            nameResId = R.string.app_language,
            value = currentLanguageDisplay,
            onClick = { _ ->
                val languageNames = mutableListOf(getString(R.string.system_default))
                val localeCodes = mutableListOf("")

                availableLocales.forEach { locale ->
                    languageNames.add(LocaleHelper.getDisplayName(locale))
                    localeCodes.add(LocaleHelper.localeToString(locale))
                }

                val currentIndex = localeCodes.indexOf(settings.appLanguage ?: "").coerceAtLeast(0)

                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_language)
                    .setSingleChoiceItems(languageNames.toTypedArray(), currentIndex) { dialog, which ->
                        dialog.dismiss()
                        settings.appLanguage = localeCodes[which]
                        applyChange()
                        // The locale is wrapped in attachBaseContext, so the screen has to be rebuilt.
                        requireActivity().recreate()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "vehicleInfoSettings",
            nameResId = R.string.vehicle_info_settings,
            value = getString(R.string.vehicle_info_settings_description),
            onClick = { navigateSafely(R.id.action_settingsFragment_to_vehicleInfoFragment) }
        ))
    }

    /**
     * Developer Settings (S2): the expert categories, unchanged apart from the five rows that
     * moved up to Display / Audio and the Static BSSID row, which now lives only in Wi-Fi Setup.
     */
    private fun addDeveloperItems(items: MutableList<SettingItem>) {
        if (showDeveloperCategories) {
        // --- Wireless Connection ---
        items.add(SettingItem.CategoryHeader("wirelessConnection", R.string.category_wireless))

        // The Wireless Mode picker (Helper / Native / Server) used to sit here. The app ships in
        // Native and there is no reason for anyone to leave it, so the only thing offering the
        // choice did was put a working connection one tap from being broken.
        //
        // Only the row is gone. Settings.wifiConnectionMode, its backup key and every reader in
        // the connection layer are untouched, and the rows below still branch on the other modes
        // — they simply never render while the mode is 3. Bringing the choice back is re-adding
        // this one entry.

        if (settings.wifiConnectionMode == 3) {
            val currentServiceName = settings.bluetoothManagerServiceName ?: "bluetooth_manager"
            items.add(SettingItem.SettingEntry(
                stableId = "bluetoothAdapterServiceName",
                nameResId = R.string.bluetooth_adapter_label,
                value = BluetoothHelper.getAdapterDescription(requireContext(), currentServiceName),
                onClick = { _ ->
                    val serviceNames = BluetoothHelper.listBluetoothServices()
                    val displayNames = serviceNames.map { name ->
                        BluetoothHelper.getAdapterDescription(requireContext(), name)
                    }.toTypedArray()

                    val selectedIndex = serviceNames.indexOf(currentServiceName).coerceAtLeast(0)

                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.select_bt_adapter)
                        .setSingleChoiceItems(displayNames, selectedIndex) { dialog, which ->
                            dialog.dismiss()
                            settings.bluetoothManagerServiceName = serviceNames[which]
                            applyChange(needsSessionRestart = true)
                            applyWirelessModeChange()
                            updateSettingsList()
                        }
                        .show()
                }
            ))
        }

        // Sub-setting for Headunit Server (Manual vs Auto)
        if (settings.wifiConnectionMode == 0 || settings.wifiConnectionMode == 1) {
            items.add(SettingItem.SegmentedButtonSettingEntry(
                stableId = "serverModeSelection",
                nameResId = R.string.server_mode_label,
                options = listOf(getString(R.string.server_mode_manual), getString(R.string.server_mode_auto)),
                selectedIndex = if (settings.wifiConnectionMode == 0) 0 else 1,
                onOptionSelected = { index ->
                    settings.wifiConnectionMode = if (index == 0) 0 else 1
                    applyChange(needsSessionRestart = true)
                    applyWirelessModeChange()
                    updateSettingsList()
                }
            ))

            // Mode 1 (Auto Server) can also use the auto-hotspot feature
            if (settings.wifiConnectionMode == 1) {
                addHotspotToggle(items)
            }
        }

        // Sub-setting for Wireless Helper Strategy
        if (settings.wifiConnectionMode == 2) {
            val helperStrategies = resources.getStringArray(R.array.helper_strategies)
            items.add(SettingItem.SettingEntry(
                stableId = "helperStrategy",
                nameResId = R.string.helper_strategy_label,
                value = helperStrategies.getOrElse(settings.helperConnectionStrategy) { "" },
                onClick = {
                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.helper_strategy_label)
                        .setSingleChoiceItems(helperStrategies, settings.helperConnectionStrategy) { dialog, which ->
                            settings.helperConnectionStrategy = which
                            applyChange(needsSessionRestart = true)
                            applyWirelessModeChange()
                            dialog.dismiss()
                            updateSettingsList()
                        }
                        .show()
                }
            ))

            // Mode 2 only shows Hotspot toggle for Strategy 4 (Headunit Hotspot)
            if (settings.helperConnectionStrategy == 4) {
                addHotspotToggle(items)
            }

            if (settings.helperConnectionStrategy == 1) { // WiFi Direct (P2P)
                items.add(SettingItem.ToggleSettingEntry(
                    stableId = "waitForWifi",
                    nameResId = R.string.wait_for_wifi,
                    descriptionResId = R.string.wait_for_wifi_description,
                    isChecked = settings.waitForWifiBeforeWifiDirect,
                    onCheckedChanged = { isChecked ->
                        settings.waitForWifiBeforeWifiDirect = isChecked
                        applyChange()
                        updateSettingsList()
                    }
                ))

                if (settings.waitForWifiBeforeWifiDirect == true) {
                    items.add(SettingItem.SliderSettingEntry(
                        stableId = "waitForWifiTimeout",
                        nameResId = R.string.wait_for_wifi_timeout,
                        value = "${settings.waitForWifiTimeout}s",
                        sliderValue = (settings.waitForWifiTimeout ?: 10).toFloat(),
                        valueFrom = 5f,
                        valueTo = 30f,
                        stepSize = 1f,
                        onValueChanged = { value ->
                            settings.waitForWifiTimeout = value.toInt()
                            applyChange()
                            updateSettingsList()
                        }
                    ))
                }
            }
        }

        // No Static BSSID row here: it lives on the Wi-Fi Setup screen (Settings › Connection),
        // which is where the phone helper writes it automatically and where manual entry belongs.
        // settings.staticBSSID is still loaded and written back on save so nothing is lost.

        // No USB category: this is a wireless-only head unit. Settings.useLibusb and the whole USB
        // connection path stay in place, so re-adding a toggle here is all it takes to expose it.

        // --- Automation ---
        items.add(SettingItem.CategoryHeader("automation", R.string.category_automation))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "killOnDisconnect",
            nameResId = R.string.kill_on_disconnect,
            descriptionResId = R.string.kill_on_disconnect_description,
            isChecked = settings.killOnDisconnect,
            onCheckedChanged = { isChecked ->
                if (isChecked) {
                    val conflicts = getKillOnDisconnectConflicts()
                    val hasAutoStartOnBoot = settings.autoStartOnBoot
                    val hasAutoStartOnScreenOn = settings.autoStartOnScreenOn
                    if (conflicts.isNotEmpty() || hasAutoStartOnBoot || hasAutoStartOnScreenOn) {
                        settings.killOnDisconnect = true
                        updateSettingsList()
                        showKillOnDisconnectWarning(conflicts, hasAutoStartOnBoot, hasAutoStartOnScreenOn)
                    } else {
                        settings.killOnDisconnect = true
                        applyChange()
                        updateSettingsList()
                    }
                } else {
                    settings.killOnDisconnect = false
                    applyChange()
                    updateSettingsList()
                }
            }
        ))


        // --- Navigation Settings ---
        items.add(SettingItem.CategoryHeader("navigation", R.string.category_navigation))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "gpsNavigation",
            nameResId = R.string.gps_for_navigation,
            descriptionResId = R.string.gps_for_navigation_description,
            isChecked = settings.useGpsForNavigation,
            onCheckedChanged = { isChecked ->
                settings.useGpsForNavigation = isChecked
                applyChange()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "showNavigationNotifications",
            nameResId = R.string.show_navigation_notifications,
            descriptionResId = R.string.show_navigation_notifications_description,
            isChecked = settings.showNavigationNotifications,
            onCheckedChanged = { isChecked ->
                settings.showNavigationNotifications = isChecked
                applyChange()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "fakeSpeed",
            nameResId = R.string.fake_speed_title,
            descriptionResId = R.string.fake_speed_description,
            isChecked = settings.fakeSpeed,
            onCheckedChanged = { isChecked ->
                settings.fakeSpeed = isChecked
                applyChange()
                updateSettingsList()
            }
        ))

        // --- Graphic Settings ---
        items.add(SettingItem.CategoryHeader("graphic", R.string.category_graphic))



        items.add(SettingItem.SettingEntry(
            stableId = "customInsets",
            nameResId = R.string.custom_insets,
            value = "${settings.insetLeft}, ${settings.insetTop}, ${settings.insetRight}, ${settings.insetBottom}",
            onClick = {
                showCustomInsetsDialog()
            }
        ))

        // Screen mode moved to Settings › Display.

        items.add(SettingItem.SettingEntry(
            stableId = "viewMode",
            nameResId = R.string.view_mode,
            value = when (settings.viewMode) {
                Settings.ViewMode.SURFACE -> getString(R.string.surface_view)
                Settings.ViewMode.TEXTURE -> getString(R.string.texture_view)
                Settings.ViewMode.GLES -> getString(R.string.gles_view)
                else -> getString(R.string.surface_view)
            },
            onClick = { _ ->
                val viewModes = arrayOf(getString(R.string.surface_view), getString(R.string.texture_view), getString(R.string.gles_view))
                val currentIdx = settings.viewMode.value
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_view_mode)
                    .setSingleChoiceItems(viewModes, currentIdx) { dialog, which ->
                        settings.viewMode = Settings.ViewMode.fromInt(which)!!
                        applyChange(needsSessionRestart = true)
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "screenOrientation",
            nameResId = R.string.screen_orientation,
            value = resources.getStringArray(R.array.screen_orientation)[settings.screenOrientation.value],
            onClick = { _ ->
                val orientationOptions = resources.getStringArray(R.array.screen_orientation)
                val currentIdx = settings.screenOrientation.value
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_screen_orientation)
                    .setSingleChoiceItems(orientationOptions, currentIdx) { dialog, whiches ->
                        val newOrientation = Settings.ScreenOrientation.fromInt(whiches) ?: Settings.ScreenOrientation.SYSTEM
                        settings.screenOrientation = newOrientation

                        // Apply immediately
                        settings.screenOrientation = newOrientation
                        settings.commit()

                        requireActivity().requestedOrientation = newOrientation.androidOrientation
                        requireContext().sendBroadcast(Intent(AapService.ACTION_ORIENTATION_CHANGED).apply {
                            setPackage(requireContext().packageName)
                        })

                        applyChange()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // Stretch to fill moved to Settings › Display.

        if (settings.viewMode == Settings.ViewMode.SURFACE) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "forcedScale",
                nameResId = R.string.forced_scale,
                descriptionResId = R.string.forced_scale_description,
                isChecked = settings.forcedScale,
                onCheckedChanged = { isChecked ->
                    // Read live by the scaler, same as stretchToFill — no session restart.
                    settings.forcedScale = isChecked
                    applyChange()
                    updateSettingsList()
                }
            ))
        }

        items.add(SettingItem.SettingEntry(
            stableId = "loadingScreen",
            nameResId = R.string.loading_screen,
            value = if (settings.loadingScreenMediaPath.isNullOrEmpty())
                getString(R.string.loading_screen_default)
            else getString(R.string.loading_screen_custom),
            onClick = {
                findNavController().navigate(R.id.action_settingsFragment_to_loadingScreenFragment)
            }
        ))

        // --- Video Settings ---
        items.add(SettingItem.CategoryHeader("video", R.string.category_video))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "forceSoftwareDecoding",
            nameResId = R.string.force_software_decoding,
            descriptionResId = R.string.force_software_decoding_description,
            isChecked = settings.forceSoftwareDecoding,
            onCheckedChanged = { isChecked ->
                settings.forceSoftwareDecoding = isChecked
                applyChange(needsSessionRestart = true)
                updateSettingsList()
            }
        ))

        if (settings.forceSoftwareDecoding == true) {
            items.add(SettingItem.SettingEntry(
                stableId = "softwareVideoDecoder",
                nameResId = R.string.software_video_decoder,
                value = when (settings.softwareVideoDecoder) {
                    Settings.SoftwareVideoDecoder.DEVICE_MEDIACODEC -> getString(R.string.software_video_decoder_device)
                    Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG -> getString(R.string.software_video_decoder_bundled)
                    null -> ""
                },
                onClick = { _ ->
                    val decoders = arrayOf(
                        getString(R.string.software_video_decoder_bundled),
                        getString(R.string.software_video_decoder_device)
                    )
                    val decoderValues = arrayOf(
                        Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG,
                        Settings.SoftwareVideoDecoder.DEVICE_MEDIACODEC
                    )
                    val currentDecoderIndex = decoderValues.indexOf(settings.softwareVideoDecoder).coerceAtLeast(0)
                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.software_video_decoder)
                        .setSingleChoiceItems(decoders, currentDecoderIndex) { dialog, which ->
                            settings.softwareVideoDecoder = decoderValues[which]
                            applyChange(needsSessionRestart = true)
                            dialog.dismiss()
                            updateSettingsList()
                        }
                        .show()
                }
            ))
        }

        items.add(SettingItem.SettingEntry(
            stableId = "videoCodec",
            nameResId = R.string.video_codec,
            value = settings.videoCodec,
            onClick = { _ ->
                val codecs = arrayOf("Auto", "H.264", "H.265")
                val currentCodecIndex = codecs.indexOf(settings.videoCodec)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.video_codec)
                    .setSingleChoiceItems(codecs, currentCodecIndex) { dialog, which ->
                        settings.videoCodec = codecs[which]
                        applyChange(needsSessionRestart = true)
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "fpsLimit",
            nameResId = R.string.fps_limit,
            value = "${settings.fpsLimit} FPS",
            onClick = { _ ->
                val fpsOptions = arrayOf("30", "60")
                val currentFpsIndex = fpsOptions.indexOf(settings.fpsLimit.toString())
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.fps_limit)
                    .setSingleChoiceItems(fpsOptions, currentFpsIndex) { dialog, which ->
                        settings.fpsLimit = fpsOptions[which].toInt()
                        applyChange(needsSessionRestart = true)
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // --- Input Settings ---
        items.add(SettingItem.CategoryHeader("input", R.string.category_input))

        items.add(SettingItem.SettingEntry(
            stableId = "keymap",
            nameResId = R.string.keymap,
            value = getString(R.string.keymap_description),
            onClick = { _ ->
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_keymapFragment)
                } catch (e: Exception) {
                    // Failover
                }
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "enableRotary",
            nameResId = R.string.enable_rotary,
            descriptionResId = R.string.enable_rotary_description,
            isChecked = settings.enableRotary,
            onCheckedChanged = { isChecked ->
                settings.enableRotary = isChecked
                applyChange(needsSessionRestart = true)
                updateSettingsList()
            }
        ))

        // --- Audio Settings ---
        items.add(SettingItem.CategoryHeader("audio", R.string.category_audio))

        // Audio Sink and Static Audio Focus moved to Settings › Audio.

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "separateAudioStreams",
            nameResId = R.string.separate_audio_streams,
            descriptionResId = R.string.separate_audio_streams_description,
            isChecked = settings.separateAudioStreams,
            onCheckedChanged = { isChecked ->
                settings.separateAudioStreams = isChecked
                applyChange(needsSessionRestart = true)
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "useAacAudio",
            nameResId = R.string.use_aac_audio,
            descriptionResId = R.string.use_aac_audio_description,
            isChecked = settings.useAacAudio,
            onCheckedChanged = { isChecked ->
                settings.useAacAudio = isChecked
                applyChange(needsSessionRestart = true)
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "syncMediaSessionAaMetadata",
            nameResId = R.string.sync_media_session_aa_metadata,
            descriptionResId = R.string.sync_media_session_aa_metadata_description,
            isChecked = settings.syncMediaSessionWithAaMetadata,
            onCheckedChanged = { isChecked ->
                settings.syncMediaSessionWithAaMetadata = isChecked
                applyChange()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "audioVolumeOffsets",
            nameResId = R.string.audio_volume_offset,
            value = "${(100 + (settings.mediaVolumeOffset))}% / ${(100 + (settings.assistantVolumeOffset))}% / ${(100 + (settings.navigationVolumeOffset))}%",
            onClick = {
                showAudioOffsetsDialog()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "audioLatencyMultiplier",
            nameResId = R.string.audio_latency_multiplier,
            value = "${settings.audioLatencyMultiplier}x",
            onClick = { _ ->
                val options = arrayOf("1x (Lowest Latency)", "2x (Low Latency)", "4x (High Latency)", "8x (Very High Latency)")
                val values = intArrayOf(1, 2, 4, 8)
                val currentIndex = values.indexOf(settings.audioLatencyMultiplier ?: 8).coerceAtLeast(0)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.audio_latency_multiplier)
                    .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                        settings.audioLatencyMultiplier = values[which]
                        applyChange(needsSessionRestart = true)
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "audioQueueCapacity",
            nameResId = R.string.audio_queue_capacity,
            value = if (settings.audioQueueCapacity == 0) "Unbounded (Legacy)" else "${settings.audioQueueCapacity} chunks",
            onClick = { _ ->
                val options = arrayOf("10 chunks (Low Latency)", "20 chunks (Balanced)", "50 chunks (High Latency)", "Unbounded (Max Backlog)")
                val values = intArrayOf(10, 20, 50, 0)
                val currentIndex = values.indexOf(settings.audioQueueCapacity).coerceAtLeast(0)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.audio_queue_capacity)
                    .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                        settings.audioQueueCapacity = values[which]
                        applyChange(needsSessionRestart = true)
                        dialog.dismiss()
                        updateSettingsList()
                    }
                .show()
            }
        ))

        // Sub-screen last, so the toggles above stay together.
        items.add(SettingItem.SettingEntry(
            stableId = "micSettings",
            nameResId = R.string.microphone_settings,
            value = getString(R.string.microphone_settings_description),
            onClick = { _ ->
                findNavController().navigate(R.id.action_settingsFragment_to_micSettingsFragment)
            }
        ))

        // --- Vehicle ---
        // Only the identifier lives here. The rest of the vehicle information (name, make, model,
        // …) is ordinary end-user data and stays under General; this one is a pairing key the
        // phone remembers, so it belongs with the expert settings — see vehicle_id_description.
        items.add(SettingItem.CategoryHeader("vehicle", R.string.category_vehicle))

        items.add(SettingItem.SettingEntry(
            stableId = "vehicleId",
            nameResId = R.string.vehicle_id_label,
            value = settings.vehicleId,
            onClick = {
                DialogUtils.showTextInputDialogWithMessage(
                    requireContext(),
                    R.string.vehicle_id_label,
                    R.string.vehicle_id_description,
                    settings.vehicleId
                ) { value ->
                    settings.vehicleId = value
                    applyChange()
                    updateSettingsList()
                }
            }
        ))

        // --- Debug Settings ---
        items.add(SettingItem.CategoryHeader("debug", R.string.category_debug))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "showFpsCounter",
            nameResId = R.string.show_fps_counter,
            descriptionResId = R.string.show_fps_counter_description,
            isChecked = settings.showFpsCounter,
            onCheckedChanged = { isChecked ->
                settings.showFpsCounter = isChecked
                applyChange()
                updateSettingsList()
            }
        ))

        val logLevels = LogExporter.LogLevel.entries
        val logLevelNames = logLevels.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }.toTypedArray()
        items.add(SettingItem.SettingEntry(
            stableId = "logLevel",
            nameResId = R.string.log_level,
            value = settings.exporterLogLevel.name.lowercase().replaceFirstChar { it.uppercase() },
            onClick = {
                val currentIndex = logLevels.indexOf(settings.exporterLogLevel)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.log_level)
                    .setSingleChoiceItems(logLevelNames, currentIndex) { dialog, which ->
                        val newLevel = logLevels[which]
                        settings.exporterLogLevel = newLevel
                        if (newLevel == LogExporter.LogLevel.SILENT) {
                            settings.exporterCaptureEnabled = false
                            if (settings.logSource == Settings.LogSource.APPLOG_FILE) {
                                AppLog.init(settings, requireContext().applicationContext)
                            } else if (LogExporter.isCapturing) {
                                LogExporter.stopCapture()
                            }
                        }
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        val logSources = Settings.LogSource.entries
        val logSourceNames = logSources.map {
            when (it) {
                Settings.LogSource.LOGCAT -> getString(R.string.log_source_logcat)
                Settings.LogSource.APPLOG_FILE -> getString(R.string.log_source_applog_file)
            }
        }.toTypedArray()
        items.add(SettingItem.SettingEntry(
            stableId = "logSource",
            nameResId = R.string.log_source,
            value = when (settings.logSource) {
                Settings.LogSource.LOGCAT -> getString(R.string.log_source_logcat)
                Settings.LogSource.APPLOG_FILE -> getString(R.string.log_source_applog_file)
            },
            onClick = {
                val currentIndex = logSources.indexOf(settings.logSource)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.log_source)
                    .setSingleChoiceItems(logSourceNames, currentIndex) { dialog, which ->
                        val newSource = logSources[which]
                        settings.logSource = newSource
                        if (newSource == Settings.LogSource.APPLOG_FILE && LogExporter.isCapturing) {
                            LogExporter.stopCapture()
                        }
                        AppLog.init(settings, requireContext().applicationContext)
                        if (newSource == Settings.LogSource.APPLOG_FILE && settings.exporterCaptureEnabled && !AppLog.isCapturing) {
                            settings.exporterCaptureEnabled = false
                        }
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "captureLog",
            nameResId = if (if (settings.logSource == Settings.LogSource.APPLOG_FILE) AppLog.isCapturing else LogExporter.isCapturing) R.string.stop_log_capture else R.string.start_log_capture,
            value = when {
                settings.exporterLogLevel == LogExporter.LogLevel.SILENT -> getString(R.string.start_log_capture_description)
                LogExporter.isCapturing -> getString(R.string.stop_log_capture_description)
                else -> getString(R.string.start_log_capture_description)
            },
            onClick = {
                val context = requireContext()
                val exporterLevel = settings.exporterLogLevel
                if (exporterLevel == LogExporter.LogLevel.SILENT) {
                    Toast.makeText(context, getString(R.string.start_log_capture_in_silent), Toast.LENGTH_LONG).show()
                    return@SettingEntry
                }

                if (settings.logSource == Settings.LogSource.APPLOG_FILE) {
                    val shouldStart = !AppLog.isCapturing
                    settings.exporterCaptureEnabled = shouldStart
                    AppLog.init(settings, context.applicationContext)
                    if (shouldStart && !AppLog.isCapturing) {
                        settings.exporterCaptureEnabled = false
                    }
                } else {
                    if (LogExporter.isCapturing) {
                        LogExporter.stopCapture()
                        settings.exporterCaptureEnabled = false
                    } else {
                        LogExporter.startCapture(context, exporterLevel)
                        settings.exporterCaptureEnabled = true
                    }
                }
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "exportLogs",
            nameResId = R.string.export_logs,
            value = getString(R.string.export_logs_description),
            onClick = {
                val context = requireContext()
                val exporterLevel = settings.exporterLogLevel
                if (exporterLevel == LogExporter.LogLevel.SILENT) {
                    Toast.makeText(context, getString(R.string.failed_export_in_silent_logs), Toast.LENGTH_LONG).show()
                    return@SettingEntry
                }

                if (settings.logSource == Settings.LogSource.APPLOG_FILE) {
                    if (AppLog.isCapturing) {
                        settings.exporterCaptureEnabled = false
                        AppLog.init(settings, context.applicationContext)
                    }
                } else if (LogExporter.isCapturing) {
                    LogExporter.stopCapture()
                }
                val logFile = LogExporter.saveLogToPublicFile(context, exporterLevel)
                updateSettingsList()

                if (logFile != null) {
                    MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
                        .setTitle(R.string.logs_exported)
                        .setMessage(getString(R.string.log_saved_to, logFile.absolutePath))
                        .setPositiveButton(R.string.share) { _, _ ->
                            LogExporter.shareLogFile(context, logFile)
                        }
                        .setNegativeButton(R.string.close) { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                } else {
                    Toast.makeText(context, getString(R.string.failed_export_logs), Toast.LENGTH_SHORT).show()
                }
            }
        ))

        // --- Reset Settings (moved here from the main screen; destructive, so it sits last) ---
        items.add(SettingItem.CategoryHeader("resetSettingsCategory", R.string.reset))
        items.add(SettingItem.SettingEntry(
            stableId = "resetSettings",
            nameResId = R.string.reset_settings,
            value = getString(R.string.reset_settings_description),
            onClick = {
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.reset_settings)
                    .setMessage(R.string.reset_settings_confirm)
                    .setPositiveButton(R.string.reset) { _, _ ->
                        settings.reset()

                        // Proper App Restart
                        val intent = requireActivity().packageManager.getLaunchIntentForPackage(requireActivity().packageName)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        requireActivity().startActivity(intent)
                        requireActivity().finish()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        ))

        } // end developer categories (Debug / Reset)
    }

    private data class ImportSnapshot(
        val wifiConnectionMode: Int,
        val helperConnectionStrategy: Int,
        val bluetoothManagerServiceName: String,
        val appLanguage: String,
        val appTheme: Settings.AppTheme,
        val useExtremeDarkMode: Boolean,
        val screenOrientation: Settings.ScreenOrientation
    )

    private fun startExportSettings() {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            options.add(getString(R.string.export_settings_choose_location) to { launchExportSettingsPicker() })
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            options.add(getString(R.string.export_settings_downloads) to { exportSettingsDownloadsWithPermission() })
        }
        options.add(getString(R.string.export_settings_app_folder) to { exportSettingsLegacy() })
        options.add(getString(R.string.share_settings_backup) to { shareNewSettingsBackup() })

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.export_settings)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second.invoke()
            }
            .show()
    }

    private fun launchExportSettingsPicker() {
        try {
            exportSettingsLauncher.launch(SettingsBackupManager.defaultFileName())
        } catch (e: ActivityNotFoundException) {
            showNoFilePickerDialog(R.string.export_settings_app_folder) { exportSettingsLegacy() }
        }
    }

    private fun exportSettingsToUri(uri: Uri) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SettingsBackupManager.exportToUri(appContext, uri)
                }
                Toast.makeText(requireContext(), R.string.settings_exported, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Toast.makeText(requireContext(), getString(R.string.settings_export_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportSettingsLegacy() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    SettingsBackupManager.exportToLegacyFile(appContext)
                }
                showSettingsExportedDialog(file)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Toast.makeText(requireContext(), getString(R.string.settings_export_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportSettingsDownloadsWithPermission() {
        runWithDownloadsStoragePermission {
            exportSettingsDownloads()
        }
    }

    private fun runWithDownloadsStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingStorageAction = action
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    private fun exportSettingsDownloads() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    SettingsBackupManager.exportToDownloadsFile(appContext)
                }
                showSettingsExportedDialog(file)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Toast.makeText(requireContext(), getString(R.string.settings_export_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSettingsExportedDialog(file: File) {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.settings_exported)
            .setMessage(getString(R.string.settings_backup_saved_to, file.absolutePath))
            .setPositiveButton(R.string.share) { _, _ -> shareSettingsBackup(file) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun shareNewSettingsBackup() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    SettingsBackupManager.exportToLegacyFile(appContext)
                }
                shareSettingsBackup(file)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Toast.makeText(requireContext(), getString(R.string.settings_export_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shareSettingsBackup(file: File) {
        val context = requireContext()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = SettingsBackupManager.MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_settings_backup)))
        } catch (e: ActivityNotFoundException) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.settings_exported)
                .setMessage(getString(R.string.settings_backup_saved_to, file.absolutePath))
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }


    // Nothing to discard any more: every row is already written when it is touched.
    private fun startImportSettings() {
        showImportSettingsOptions()
    }

    private fun showImportSettingsOptions() {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options.add(getString(R.string.import_settings_choose_file) to { launchImportSettingsPicker() })
        if (SettingsBackupManager.canAccessDownloadsDirectory()) {
            options.add(getString(R.string.import_settings_downloads) to { showDownloadsBackupFilePickerWithPermission() })
        }
        options.add(getString(R.string.import_settings_app_folder) to { showAppBackupFilePicker() })

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.import_settings)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second.invoke()
            }
            .show()
    }

    private fun launchImportSettingsPicker() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                importSettingsLauncher.launch(SettingsBackupManager.IMPORT_MIME_TYPES.copyOf())
            } else {
                legacyImportSettingsLauncher.launch(SettingsBackupManager.MIME_TYPE)
            }
        } catch (e: ActivityNotFoundException) {
            if (SettingsBackupManager.canAccessDownloadsDirectory()) {
                showNoFilePickerDialog(R.string.import_settings_downloads) {
                    showDownloadsBackupFilePickerWithPermission()
                }
            } else {
                showNoFilePickerDialog(R.string.import_settings_app_folder) { showAppBackupFilePicker() }
            }
        }
    }

    private fun showNoFilePickerDialog(fallbackLabelRes: Int, onFallback: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.no_file_picker_title)
            .setMessage(R.string.no_file_picker_message)
            .setPositiveButton(fallbackLabelRes) { _, _ -> onFallback() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAppBackupFilePicker() {
        val backupFiles = SettingsBackupManager.findBackupFiles(appBackupDirectories())
        if (backupFiles.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.no_settings_backups_title)
                .setMessage(R.string.no_settings_backups_message)
                .setPositiveButton(R.string.export_settings_app_folder) { _, _ -> exportSettingsLegacy() }
                .setNegativeButton(R.string.close, null)
                .show()
            return
        }

        val labels = backupFiles.map { file ->
            "${file.name}\n${file.parent ?: ""}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.import_settings_app_folder)
            .setItems(labels) { _, which ->
                importSettingsFromFile(backupFiles[which])
            }
            .show()
    }

    private fun showDownloadsBackupFilePickerWithPermission() {
        runWithDownloadsStoragePermission {
            showDownloadsBackupFilePicker()
        }
    }

    private fun showDownloadsBackupFilePicker() {
        val backupFiles = SettingsBackupManager.findBackupFiles(listOf(SettingsBackupManager.downloadsDirectory()))
        if (backupFiles.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.no_settings_backups_title)
                .setMessage(R.string.no_settings_backups_downloads_message)
                .setPositiveButton(R.string.export_settings_downloads) { _, _ -> exportSettingsDownloadsWithPermission() }
                .setNegativeButton(R.string.close, null)
                .show()
            return
        }

        val labels = backupFiles.map { file ->
            "${file.name}\n${file.parent ?: ""}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.import_settings_downloads)
            .setItems(labels) { _, which ->
                importSettingsFromFile(backupFiles[which])
            }
            .show()
    }

    private fun appBackupDirectories(): List<File?> {
        return SettingsBackupManager.backupSearchDirectories(
            requireContext().getExternalFilesDir(null),
            requireContext().cacheDir,
            null
        )
    }

    private fun importSettingsFromUri(uri: Uri) {
        val appContext = requireContext().applicationContext
        val snapshot = createImportSnapshot()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SettingsBackupManager.importFromUri(appContext, uri)
                }
                handleImportedSettings(snapshot, result)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Toast.makeText(requireContext(), getString(R.string.settings_import_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importSettingsFromFile(file: File) {
        val appContext = requireContext().applicationContext
        val snapshot = createImportSnapshot()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SettingsBackupManager.importFromFile(appContext, file)
                }
                handleImportedSettings(snapshot, result)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Toast.makeText(requireContext(), getString(R.string.settings_import_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createImportSnapshot(): ImportSnapshot {
        return ImportSnapshot(
            wifiConnectionMode = settings.wifiConnectionMode,
            helperConnectionStrategy = settings.helperConnectionStrategy,
            bluetoothManagerServiceName = settings.bluetoothManagerServiceName,
            appLanguage = settings.appLanguage,
            appTheme = settings.appTheme,
            useExtremeDarkMode = settings.useExtremeDarkMode,
            screenOrientation = settings.screenOrientation
        )
    }

    private fun handleImportedSettings(snapshot: ImportSnapshot, result: SettingsBackupManager.ImportResult) {
        settings = App.provide(requireContext()).settings
        applyWirelessSideEffects(snapshot)

        if (SettingsBackupManager.requiresProjectionRestart(result.changedKeys)) {
            requestSessionRestart()
        }

        updateSettingsList()

        Toast.makeText(
            requireContext(),
            getString(R.string.settings_imported, result.importedKeys, result.skippedKeys),
            Toast.LENGTH_LONG
        ).show()

        if (shouldRecreateAfterImport(snapshot)) {
            requireActivity().recreate()
        }
    }

    private fun applyWirelessSideEffects(snapshot: ImportSnapshot, context: Context = requireContext()) {
        if (snapshot.wifiConnectionMode != settings.wifiConnectionMode ||
            snapshot.helperConnectionStrategy != settings.helperConnectionStrategy ||
            snapshot.bluetoothManagerServiceName != settings.bluetoothManagerServiceName) {
            val intent = Intent(context, AapService::class.java).apply {
                val mode = settings.wifiConnectionMode
                action = if (mode == 1 || mode == 2 || mode == 3)
                    AapService.ACTION_START_WIRELESS else AapService.ACTION_STOP_WIRELESS
            }
            context.startService(intent)
        }
    }

    private fun shouldRecreateAfterImport(snapshot: ImportSnapshot): Boolean {
        return snapshot.appLanguage != settings.appLanguage ||
            snapshot.appTheme != settings.appTheme ||
            snapshot.useExtremeDarkMode != settings.useExtremeDarkMode ||
            snapshot.screenOrientation != settings.screenOrientation
    }

    private fun showAudioOffsetsDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_audio_offsets, null)

        val seekMedia = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_media)
        val seekAssistant = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_assistant)
        val seekNavigation = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_navigation)

        val textMedia = dialogView.findViewById<android.widget.TextView>(R.id.text_media_val)
        val textAssistant = dialogView.findViewById<android.widget.TextView>(R.id.text_assistant_val)
        val textNavigation = dialogView.findViewById<android.widget.TextView>(R.id.text_navigation_val)

        // Mapping: 0 to 100 on SeekBar -> 0% to 200% Gain. Default is 50 (100% Gain, 0 Offset)
        // Offset = (seekValue - 50) * 2
        // seekValue = (offset / 2) + 50

        seekMedia.progress = ((settings.mediaVolumeOffset) / 2) + 50
        seekAssistant.progress = ((settings.assistantVolumeOffset) / 2) + 50
        seekNavigation.progress = ((settings.navigationVolumeOffset) / 2) + 50

        val updateLabels = {
            textMedia.text = "${(seekMedia.progress * 2)}%"
            textAssistant.text = "${(seekAssistant.progress * 2)}%"
            textNavigation.text = "${(seekNavigation.progress * 2)}%"
        }
        updateLabels()

        val listener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        }

        seekMedia.setOnSeekBarChangeListener(listener)
        seekAssistant.setOnSeekBarChangeListener(listener)
        seekNavigation.setOnSeekBarChangeListener(listener)

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.audio_volume_offset)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                settings.mediaVolumeOffset = (seekMedia.progress - 50) * 2
                settings.assistantVolumeOffset = (seekAssistant.progress - 50) * 2
                settings.navigationVolumeOffset = (seekNavigation.progress - 50) * 2
                applyChange()
                updateSettingsList()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPermissionDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.hotspot_permission_title)
            .setMessage(R.string.hotspot_permission_message)
            .setPositiveButton(R.string.open_settings) { dialog, _ ->
                val intent = Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                startActivity(intent)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                settings.autoEnableHotspot = false
                applyChange()
                updateSettingsList()
            }
            .show()
    }

    private fun showExperimentalWarning() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.hotspot_warning_title)
            .setMessage(R.string.hotspot_warning_message)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                settings.autoEnableHotspot = true
                applyChange()
                updateSettingsList()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                settings.autoEnableHotspot = false
                applyChange()
                updateSettingsList()
            }
            .show()
    }

    private fun showCustomInsetsDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_insets, null)

        val inputLeft = dialogView.findViewById<EditText>(R.id.input_left)
        val inputTop = dialogView.findViewById<EditText>(R.id.input_top)
        val inputRight = dialogView.findViewById<EditText>(R.id.input_right)
        val inputBottom = dialogView.findViewById<EditText>(R.id.input_bottom)

        // Set initial values from pending state
        inputLeft.setText((settings.insetLeft).toString())
        inputTop.setText((settings.insetTop).toString())
        inputRight.setText((settings.insetRight).toString())
        inputBottom.setText((settings.insetBottom).toString())

        // Helper to update pending values and UI preview
        fun updatePreview() {
            val l = inputLeft.text.toString().toIntOrNull() ?: 0
            val t = inputTop.text.toString().toIntOrNull() ?: 0
            val r = inputRight.text.toString().toIntOrNull() ?: 0
            val b = inputBottom.text.toString().toIntOrNull() ?: 0

            settings.insetLeft = l
            settings.insetTop = t
            settings.insetRight = r
            settings.insetBottom = b

            // Live Preview: Set padding on the root view of the Activity
            val root = requireActivity().findViewById<View>(R.id.settings_nav_host)
            root?.setPadding(l, t, r, b)
        }

        // Helper to bind buttons
        fun bindButton(btnId: Int, input: EditText, delta: Int) {
            dialogView.findViewById<View>(btnId).setOnClickListener {
                val current = input.text.toString().toIntOrNull() ?: 0
                val newVal = (current + delta).coerceAtLeast(0)
                input.setText(newVal.toString())
                updatePreview()
            }
        }

        bindButton(R.id.btn_left_minus, inputLeft, -10)
        bindButton(R.id.btn_left_plus, inputLeft, 10)
        bindButton(R.id.btn_top_minus, inputTop, -10)
        bindButton(R.id.btn_top_plus, inputTop, 10)
        bindButton(R.id.btn_right_minus, inputRight, -10)
        bindButton(R.id.btn_right_plus, inputRight, 10)
        bindButton(R.id.btn_bottom_minus, inputBottom, -10)
        bindButton(R.id.btn_bottom_plus, inputBottom, 10)

        // Text Watchers? Maybe overkill, buttons are safer.
        // Let's add simple focus change listener to update preview on manual entry
        val focusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updatePreview()
        }
        inputLeft.onFocusChangeListener = focusListener
        inputTop.onFocusChangeListener = focusListener
        inputRight.onFocusChangeListener = focusListener
        inputBottom.onFocusChangeListener = focusListener

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.custom_insets)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val l = inputLeft.text.toString().toIntOrNull() ?: 0
                val t = inputTop.text.toString().toIntOrNull() ?: 0
                val r = inputRight.text.toString().toIntOrNull() ?: 0
                val b = inputBottom.text.toString().toIntOrNull() ?: 0

                // PERSIST IMMEDIATELY (Rescue Mode)
                settings.insetLeft = l
                settings.insetTop = t
                settings.insetRight = r
                settings.insetBottom = b
                settings.commit()

                // Update pending to keep UI in sync
                settings.insetLeft = l
                settings.insetTop = t
                settings.insetRight = r
                settings.insetBottom = b

                applyChange()
                updateSettingsList()
                dialog.dismiss()

                // Refresh activity to apply padding immediately
                requireActivity().recreate()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                // Revert Preview immediately
                val root = requireActivity().findViewById<View>(R.id.settings_nav_host)
                root?.setPadding(
                    settings.insetLeft, settings.insetTop,
                    settings.insetRight, settings.insetBottom
                )
                // Reset pending to old values
                settings.insetLeft = settings.insetLeft
                settings.insetTop = settings.insetTop
                settings.insetRight = settings.insetRight
                settings.insetBottom = settings.insetBottom

                dialog.dismiss()
            }
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        // Refresh settings list when returning from sub-screens (e.g. AutoConnectFragment, DarkModeFragment)
        if (::settingsAdapter.isInitialized) {
            settings = App.provide(requireContext()).settings
            // Mic input source has no entry on this screen but is written back on save, so pick up
            // any change made in Mic Settings — otherwise saving here reverts it. Only values this
            // screen cannot edit may be refreshed, or we would discard the user's unsaved changes.
            settings.micInputSource = settings.micInputSource
            // Same for the static BSSID: since v1.0.80 this screen has no row for it (it lives on
            // Wi-Fi Setup, where the phone helper writes it), but save still writes it back.
            settings.staticBSSID = settings.staticBSSID
            updateSettingsList()
        }
    }

    private fun getKillOnDisconnectConflicts(): List<String> {
        val conflicts = mutableListOf<String>()
        // Only reconnection-related settings conflict with close-on-disconnect.
        // Initial connection settings (auto-connect last session, single USB,
        // self mode, auto-start on USB) should keep working when the car starts.
        if (settings.reopenOnReconnection) {
            conflicts.add(getString(R.string.reopen_on_reconnection_label))
        }
        return conflicts
    }

    private fun showKillOnDisconnectWarning(conflicts: List<String>, hasAutoStartOnBoot: Boolean, hasAutoStartOnScreenOn: Boolean = false) {
        val message = buildString {
            if (conflicts.isNotEmpty()) {
                val conflictList = conflicts.joinToString("\n") { "• $it" }
                append(getString(R.string.kill_on_disconnect_warning, conflictList))
            }
            if (hasAutoStartOnBoot) {
                if (conflicts.isNotEmpty()) append("\n\n")
                append(getString(R.string.kill_on_disconnect_boot_warning))
            }
            if (hasAutoStartOnScreenOn) {
                if (conflicts.isNotEmpty() || hasAutoStartOnBoot) append("\n\n")
                append(getString(R.string.kill_on_disconnect_screen_on_warning))
            }
        }

        var confirmed = false

        val hasDisableableConflicts = conflicts.isNotEmpty()
        val positiveTextRes = if (hasDisableableConflicts) {
            R.string.kill_on_disconnect_disable_and_enable
        } else {
            R.string.kill_on_disconnect_enable_anyway
        }

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.kill_on_disconnect_warning_title)
            .setMessage(message)
            .setPositiveButton(positiveTextRes) { _, _ ->
                confirmed = true
                if (hasDisableableConflicts) {
                    disableKillOnDisconnectConflicts()
                    Toast.makeText(context, getString(R.string.kill_on_disconnect_conflicts_disabled), Toast.LENGTH_LONG).show()
                }
                settings.killOnDisconnect = true
                applyChange()
                updateSettingsList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()

        // Disable the positive button and show a countdown
        val positiveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        positiveButton.isEnabled = false
        positiveButton.alpha = 0.4f
        val baseText = getString(positiveTextRes)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var remaining = 4

        val countdownRunnable = object : Runnable {
            override fun run() {
                if (remaining > 0) {
                    positiveButton.text = "$baseText (${remaining}s)"
                    remaining--
                    handler.postDelayed(this, 1000)
                } else {
                    positiveButton.text = baseText
                    positiveButton.isEnabled = true
                    positiveButton.alpha = 1.0f
                }
            }
        }
        handler.post(countdownRunnable)

        dialog.setOnDismissListener {
            handler.removeCallbacks(countdownRunnable)
            if (!confirmed) {
                settings.killOnDisconnect = false
                applyChange()
                updateSettingsList()
            }
        }
    }

    private fun disableKillOnDisconnectConflicts() {
        // Only disable reconnection-related settings.
        // Initial connection settings are kept so they work when the car starts.
        settings.reopenOnReconnection = false
    }

    private fun addHotspotToggle(items: MutableList<SettingItem>) {
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoEnableHotspot",
            nameResId = R.string.auto_enable_hotspot,
            descriptionResId = R.string.auto_enable_hotspot_description,
            isChecked = settings.autoEnableHotspot,
            onCheckedChanged = { isChecked ->
                if (isChecked) {
                    if (Build.VERSION.SDK_INT >= 23 && !SystemSettings.System.canWrite(requireContext())) {
                        showPermissionDialog()
                    } else {
                        showExperimentalWarning()
                    }
                } else {
                    settings.autoEnableHotspot = false
                    applyChange()
                    updateSettingsList()
                }
            }
        ))
    }


    /**
     * Live one-liner for the Permissions row. Required gaps are reported first because those are
     * the ones that stop a connection outright; recommended gaps only surface once nothing is
     * blocking. Grant state alone is enough here — the three-way "permanently denied" distinction
     * only matters on the dashboard, where it changes what a tap does.
     */
    private fun getPermissionSummary(): String {
        val specs = PermissionItem.applicableSpecs()
        val required = specs.count {
            it.kind == PermissionKind.REQUIRED && !PermissionItem.isGranted(requireContext(), it)
        }
        if (required > 0) return getString(R.string.permission_summary_required_missing, required)
        val recommended = specs.count {
            it.kind == PermissionKind.RECOMMENDED && !PermissionItem.isGranted(requireContext(), it)
        }
        if (recommended > 0) return getString(R.string.permission_summary_recommended_missing, recommended)
        return getString(R.string.permission_summary_all_granted)
    }

    private fun getAutoConnectSummary(): String {
        val order = settings.autoConnectPriorityOrder
        val enabledNames = order.mapNotNull { id ->
            val isEnabled = when (id) {
                Settings.AUTO_CONNECT_LAST_SESSION -> settings.autoConnectLastSession
                Settings.AUTO_CONNECT_SELF_MODE -> settings.autoStartSelfMode
                Settings.AUTO_CONNECT_SINGLE_USB -> settings.autoConnectSingleUsbDevice
                else -> false
            }
            if (isEnabled) {
                when (id) {
                    Settings.AUTO_CONNECT_LAST_SESSION -> getString(R.string.auto_connect_last_session)
                    Settings.AUTO_CONNECT_SELF_MODE -> getString(R.string.auto_start_self_mode)
                    Settings.AUTO_CONNECT_SINGLE_USB -> getString(R.string.auto_connect_single_usb)
                    else -> null
                }
            } else null
        }
        return if (enabledNames.isEmpty()) {
            getString(R.string.auto_connect_all_disabled)
        } else {
            enabledNames.joinToString(" → ")
        }
    }

    private fun showNumericInputDialog(
        title: String,
        message: String?,
        initialValue: Int,
        onConfirm: (Int) -> Unit
    ) {
        val context = requireContext()
        val editView = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (initialValue == 0 && title.contains("DPI", true)) "" else initialValue.toString())
        }

        // Use a container to add padding around the EditText
        val container = android.widget.FrameLayout(context)
        val params = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val margin = (24 * context.resources.displayMetrics.density).toInt()
        params.setMargins(margin, 8, margin, 8)
        container.addView(editView, params)

        val dialog = MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setView(container)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val newVal = (editView.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                onConfirm(newVal)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        editView.requestFocus()
    }

    private fun handleNativeAaSelection() {
        if (NativeAaHandshakeManager.checkCompatibility(requireContext())) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.supported_nativeaa)
                .setMessage(R.string.supported_nativeaa_desc)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    settings.wifiConnectionMode = 3
                    applyChange(needsSessionRestart = true)
                    applyWirelessModeChange()
                    updateSettingsList()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.not_supported_nativeaa)
                .setMessage(R.string.not_supported_nativeaa_desc)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    settings.wifiConnectionMode = 3
                    applyChange(needsSessionRestart = true)
                    applyWirelessModeChange()
                    updateSettingsList()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    companion object {
        /** Nav argument key: which slice of the settings tree this instance renders. */
        const val ARG_SECTION = "section"

        const val SECTION_ROOT = "ROOT"
        const val SECTION_GENERAL = "GENERAL"
        const val SECTION_DISPLAY = "DISPLAY"
        const val SECTION_AUDIO = "AUDIO"
        const val SECTION_BACKUP = "BACKUP"
        const val SECTION_INFO = "INFO"
        const val SECTION_DEVELOPER = "DEVELOPER"
    }
}
