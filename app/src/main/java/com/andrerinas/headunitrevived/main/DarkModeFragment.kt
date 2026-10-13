package com.andrerinas.headunitrevived.main

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.utils.AppThemeManager
import com.andrerinas.headunitrevived.utils.Settings
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DarkModeFragment : Fragment(), SensorEventListener {
    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar

    // Sensor for live lux reading
    private var cachedLux: Float = -1f
    private var sensorManager: SensorManager? = null

    // Live screen brightness reading (mirrors how the manager reads it)
    private var cachedBrightness: Int = -1
    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val newBrightness = readBrightness()
            if (newBrightness != cachedBrightness) {
                cachedBrightness = newBrightness
                scheduleListRefresh()
            }
        }
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable {
        if (isAdded && ::settingsAdapter.isInitialized) {
            updateSettingsList()
        }
    }
    private val nightModeApplyRunnable = Runnable {
        if (isAdded) applyNightModeChange()
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_dark_mode, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        // Intercept system back button
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

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            val newLux = event.values[0]
            if (kotlin.math.abs(newLux - cachedLux) >= 1.0f || cachedLux < 0f) {
                cachedLux = newLux
                scheduleListRefresh()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            handleBackPress()
        }
        // No Save item: each row writes straight through (see the applyXxxChange helpers).
    }

    private fun handleBackPress() {
        navigateBack()
    }

    /**
     * A row that changes the app's own theme: restart (or stop) the automatic theme manager and
     * tell every activity to redraw. Same work the Save button used to do, minus the diffing —
     * the row that called this is the thing that changed.
     */
    private fun applyThemeChange() {
        settings.commit()
        updateSettingsList()

        val theme = settings.appTheme
        App.appThemeManager?.stop()
        App.appThemeManager = null
        if (AppThemeManager.isStaticMode(theme)) {
            AppThemeManager.applyStaticTheme(settings)
        } else {
            val manager = AppThemeManager(requireContext().applicationContext, settings)
            App.appThemeManager = manager
            manager.start()
        }

        AppThemeManager.signalVisualChange()
    }

    /** A row that changes Android Auto's night mode: the service re-sends its current state. */
    private fun applyNightModeChange() {
        settings.commit()
        updateSettingsList()

        val context = context ?: return
        context.sendBroadcast(Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE).apply {
            setPackage(context.packageName)
        })
    }

    /** The desaturation slider fires continuously while dragging, so coalesce the broadcasts. */
    private fun scheduleNightModeApply() {
        refreshHandler.removeCallbacks(nightModeApplyRunnable)
        refreshHandler.postDelayed(nightModeApplyRunnable, 300)
    }

    /** The GLES dialog switches the renderer, which only takes effect on a fresh session. */
    private fun applyViewModeChange() {
        applyNightModeChange()

        val context = context ?: return
        if (!App.provide(context).commManager.isConnected) return

        Toast.makeText(context, getString(R.string.settings_session_restart_notice), Toast.LENGTH_LONG).show()
        ContextCompat.startForegroundService(context, Intent(context, AapService::class.java).apply {
            action = AapService.ACTION_STOP_SERVICE
        })
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

        // --- App Theme ---
        items.add(SettingItem.CategoryHeader("appTheme", R.string.app_theme))

        // The picker offers four modes in a fixed order (see AppThemeManager.APP_THEME_MENU);
        // the sensor / manual-time / extreme-dark modes still work if already stored, they are
        // just no longer on the menu.
        val appThemeEntries = AppThemeManager.APP_THEME_MENU
        items.add(SettingItem.SettingEntry(
            stableId = "appTheme",
            nameResId = R.string.app_theme,
            value = AppThemeManager.appThemeLabel(resources, settings.appTheme),
            onClick = { _ ->
                val labels = appThemeEntries
                    .map { AppThemeManager.appThemeLabel(resources, it) }
                    .toTypedArray()
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_app_theme)
                    // -1 when the stored mode is off-menu: nothing preselected, rather than
                    // rewriting the setting behind the user's back.
                    .setSingleChoiceItems(labels, appThemeEntries.indexOf(settings.appTheme)) { dialog, which ->
                        settings.appTheme = appThemeEntries[which]
                        // Picking a static mode clears extreme dark. Since the toggle for it is
                        // gone from this screen, this and the status-bar theme button are the
                        // only things left that can clear the flag.
                        if (settings.appTheme == Settings.AppTheme.CLEAR ||
                            settings.appTheme == Settings.AppTheme.DARK) {
                            settings.useExtremeDarkMode = false
                        }
                        applyThemeChange()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // App theme sub-options: threshold slider for Light Sensor / Screen Brightness
        if (settings.appTheme == Settings.AppTheme.LIGHT_SENSOR || settings.appTheme == Settings.AppTheme.SCREEN_BRIGHTNESS) {
            val isSensor = settings.appTheme == Settings.AppTheme.LIGHT_SENSOR
            val currentValue = if (isSensor) settings.appThemeThresholdLux else settings.appThemeThresholdBrightness
            val title = getString(if (isSensor) R.string.threshold_light_title else R.string.threshold_brightness_title)
            val hint = getString(if (isSensor) R.string.threshold_light_hint else R.string.threshold_brightness_hint)
            val currentReading = if (isSensor) {
                if (cachedLux >= 0) getString(R.string.current_light_reading, cachedLux.toInt()) else ""
            } else {
                if (cachedBrightness >= 0) getString(R.string.current_brightness_reading, cachedBrightness) else ""
            }
            val displayValue = if (isSensor) {
                val base = "${currentValue ?: 0} Lux"
                if (currentReading.isNotEmpty()) "$base ($currentReading)" else base
            } else {
                val base = "${currentValue ?: 0} / 255"
                if (currentReading.isNotEmpty()) "$base ($currentReading)" else base
            }

            items.add(SettingItem.SettingEntry(
                stableId = "appThemeThreshold",
                nameResId = if (isSensor) R.string.threshold_light_title else R.string.threshold_brightness_title,
                value = displayValue,
                onClick = { _ ->
                    if (isSensor) {
                        showLuxSliderDialog(
                            title = title,
                            message = hint,
                            initialLux = currentValue ?: 0,
                            currentReading = currentReading,
                            onConfirm = { newLux ->
                                settings.appThemeThresholdLux = newLux
                                applyThemeChange()
                                updateSettingsList()
                            }
                        )
                    } else {
                        showSliderDialog(
                            title = title,
                            message = hint,
                            initialPercentage = currentValue ?: 100,
                            minLabel = "0",
                            maxLabel = "255",
                            formatValue = { v -> "$v" },
                            currentReading = currentReading,
                            sliderMax = 255,
                            onConfirm = { newVal ->
                                settings.appThemeThresholdBrightness = newVal
                                applyThemeChange()
                                updateSettingsList()
                            }
                        )
                    }
                }
            ))

        }

        // App theme sub-options: time pickers for Manual Time
        if (settings.appTheme == Settings.AppTheme.MANUAL_TIME) {
            val formatTime = { minutes: Int -> "%02d:%02d".format(minutes / 60, minutes % 60) }

            items.add(SettingItem.SettingEntry(
                stableId = "appThemeStart",
                nameResId = R.string.night_mode_start,
                value = formatTime(settings.appThemeManualStart),
                onClick = { _ ->
                    TimePickerDialog(requireContext(), { _, hour, minute ->
                        settings.appThemeManualStart = hour * 60 + minute
                        applyThemeChange()
                        updateSettingsList()
                    }, settings.appThemeManualStart / 60, settings.appThemeManualStart % 60, true).show()
                }
            ))

            items.add(SettingItem.SettingEntry(
                stableId = "appThemeEnd",
                nameResId = R.string.night_mode_end,
                value = formatTime(settings.appThemeManualEnd),
                onClick = { _ ->
                    TimePickerDialog(requireContext(), { _, hour, minute ->
                        settings.appThemeManualEnd = hour * 60 + minute
                        applyThemeChange()
                        updateSettingsList()
                    }, settings.appThemeManualEnd / 60, settings.appThemeManualEnd % 60, true).show()
                }
            ))
        }

        // --- Android Auto Night Mode ---
        items.add(SettingItem.CategoryHeader("aaNightMode", R.string.night_mode))

        // Night Mode (Android Auto)
        // Same three-and-four split as the app theme above: Android Auto has no System mode,
        // so its menu is Automatic (Sunrise/Sunset) / Light / Dark.
        val nightModeEntries = AppThemeManager.NIGHT_MODE_MENU
        items.add(SettingItem.SettingEntry(
            stableId = "nightMode",
            nameResId = R.string.night_mode,
            value = run {
                val base = AppThemeManager.nightModeLabel(resources, settings.nightMode)
                if (settings.nightMode == Settings.NightMode.AUTO) {
                    val info = com.andrerinas.headunitrevived.utils.NightMode(settings, true).getCalculationInfo()
                    "$base ($info)"
                } else {
                    base
                }
            },
            onClick = { _ ->
                val labels = nightModeEntries
                    .map { AppThemeManager.nightModeLabel(resources, it) }
                    .toTypedArray()
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.night_mode)
                    .setSingleChoiceItems(labels, nightModeEntries.indexOf(settings.nightMode)) { dialog, which ->
                        settings.nightMode = nightModeEntries[which]
                        applyNightModeChange()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // Night mode sub-options: threshold slider for Light Sensor / Screen Brightness
        if (settings.nightMode == Settings.NightMode.LIGHT_SENSOR || settings.nightMode == Settings.NightMode.SCREEN_BRIGHTNESS) {
            val isSensor = settings.nightMode == Settings.NightMode.LIGHT_SENSOR
            val currentValue = if (isSensor) settings.nightModeThresholdLux else settings.nightModeThresholdBrightness
            val title = getString(if (isSensor) R.string.threshold_light_title else R.string.threshold_brightness_title)
            val hint = getString(if (isSensor) R.string.threshold_light_hint else R.string.threshold_brightness_hint)
            val nmCurrentReading = if (isSensor) {
                if (cachedLux >= 0) getString(R.string.current_light_reading, cachedLux.toInt()) else ""
            } else {
                if (cachedBrightness >= 0) getString(R.string.current_brightness_reading, cachedBrightness) else ""
            }
            val displayValue = if (isSensor) {
                val base = "${currentValue ?: 0} Lux"
                if (nmCurrentReading.isNotEmpty()) "$base ($nmCurrentReading)" else base
            } else {
                val base = "${currentValue ?: 0} / 255"
                if (nmCurrentReading.isNotEmpty()) "$base ($nmCurrentReading)" else base
            }

            items.add(SettingItem.SettingEntry(
                stableId = "nightModeThreshold",
                nameResId = if (isSensor) R.string.threshold_light_title else R.string.threshold_brightness_title,
                value = displayValue,
                onClick = { _ ->
                    if (isSensor) {
                        showLuxSliderDialog(
                            title = title,
                            message = hint,
                            initialLux = currentValue ?: 0,
                            currentReading = nmCurrentReading,
                            onConfirm = { newLux ->
                                settings.nightModeThresholdLux = newLux
                                applyNightModeChange()
                                updateSettingsList()
                            }
                        )
                    } else {
                        showSliderDialog(
                            title = title,
                            message = hint,
                            initialPercentage = currentValue ?: 100,
                            minLabel = "0",
                            maxLabel = "255",
                            formatValue = { v -> "$v" },
                            currentReading = nmCurrentReading,
                            sliderMax = 255,
                            onConfirm = { newVal ->
                                settings.nightModeThresholdBrightness = newVal
                                applyNightModeChange()
                                updateSettingsList()
                            }
                        )
                    }
                }
            ))

        }

        // Night mode sub-options: time pickers for Manual Time
        if (settings.nightMode == Settings.NightMode.MANUAL_TIME) {
            val formatTime = { minutes: Int -> "%02d:%02d".format(minutes / 60, minutes % 60) }

            items.add(SettingItem.SettingEntry(
                stableId = "nightModeStart",
                nameResId = R.string.night_mode_start,
                value = formatTime(settings.nightModeManualStart),
                onClick = { _ ->
                    TimePickerDialog(requireContext(), { _, hour, minute ->
                        settings.nightModeManualStart = hour * 60 + minute
                        applyNightModeChange()
                        updateSettingsList()
                    }, settings.nightModeManualStart / 60, settings.nightModeManualStart % 60, true).show()
                }
            ))

            items.add(SettingItem.SettingEntry(
                stableId = "nightModeEnd",
                nameResId = R.string.night_mode_end,
                value = formatTime(settings.nightModeManualEnd),
                onClick = { _ ->
                    TimePickerDialog(requireContext(), { _, hour, minute ->
                        settings.nightModeManualEnd = hour * 60 + minute
                        applyNightModeChange()
                        updateSettingsList()
                    }, settings.nightModeManualEnd / 60, settings.nightModeManualEnd % 60, true).show()
                }
            ))
        }

        settingsAdapter.submitList(items) {
            scrollState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor != null) {
            sensorManager?.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Observe screen brightness changes so the threshold dialog/list show a live value
        cachedBrightness = readBrightness()
        val cr = requireContext().contentResolver
        cr.registerContentObserver(
            SystemSettings.System.getUriFor(SystemSettings.System.SCREEN_BRIGHTNESS),
            false, brightnessObserver
        )
        if (Build.VERSION.SDK_INT >= 28) {
            cr.registerContentObserver(
                SystemSettings.System.getUriFor("screen_brightness_float"),
                false, brightnessObserver
            )
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        try { context?.contentResolver?.unregisterContentObserver(brightnessObserver) } catch (_: Exception) {}
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun readBrightness(): Int {
        val ctx = context ?: return -1
        val cr = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val f = SystemSettings.System.getFloat(cr, "screen_brightness_float")
                if (!f.isNaN()) return (f * 255f).toInt().coerceIn(0, 255)
            } catch (_: Exception) { /* fall through */ }
        }
        return try {
            SystemSettings.System.getInt(cr, SystemSettings.System.SCREEN_BRIGHTNESS)
                .coerceIn(0, 255)
        } catch (_: Exception) { -1 }
    }


    private fun scheduleListRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 500)
    }

    private fun showSliderDialog(
        title: String,
        message: String,
        initialPercentage: Int,
        minLabel: String,
        maxLabel: String,
        formatValue: (Int) -> String,
        currentReading: String = "",
        sliderMax: Int = 100,
        onConfirm: (Int) -> Unit
    ) {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (24 * density).toInt()

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, (8 * density).toInt(), padding, 0)
        }

        val hint = android.widget.TextView(context).apply {
            text = message
            textSize = 14f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        layout.addView(hint)

        val label = android.widget.TextView(context).apply {
            text = formatValue(initialPercentage.coerceIn(0, sliderMax))
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            val topMargin = (16 * density).toInt()
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = topMargin
            layoutParams = lp
        }
        layout.addView(label)

        val seekBar = android.widget.SeekBar(context).apply {
            max = sliderMax
            progress = initialPercentage.coerceIn(0, sliderMax)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = formatValue(progress)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        layout.addView(seekBar)

        val rangeRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
        val minText = android.widget.TextView(context).apply {
            text = minLabel
            textSize = 12f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        val maxText = android.widget.TextView(context).apply {
            text = maxLabel
            textSize = 12f
            gravity = android.view.Gravity.END
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        rangeRow.addView(minText)
        rangeRow.addView(maxText)
        layout.addView(rangeRow)

        if (currentReading.isNotEmpty()) {
            val readingLabel = android.widget.TextView(context).apply {
                text = currentReading
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                    setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (16 * density).toInt()
                layoutParams = lp
            }
            layout.addView(readingLabel)
        }

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                onConfirm(seekBar.progress)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    private fun showLuxSliderDialog(
        title: String,
        message: String,
        initialLux: Int,
        currentReading: String = "",
        onConfirm: (Int) -> Unit
    ) {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (24 * density).toInt()

        var currentMax = if (initialLux <= LUX_MAX_FINE) LUX_MAX_FINE else LUX_MAX

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, (8 * density).toInt(), padding, 0)
        }

        val hint = android.widget.TextView(context).apply {
            text = message
            textSize = 14f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        layout.addView(hint)

        val label = android.widget.TextView(context).apply {
            text = "$initialLux Lux"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (16 * density).toInt()
            layoutParams = lp
        }
        layout.addView(label)

        val seekBar = android.widget.SeekBar(context).apply {
            max = currentMax
            progress = initialLux.coerceIn(0, currentMax)
        }

        val minText = android.widget.TextView(context).apply {
            text = "0 Lux"
            textSize = 12f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val maxText = android.widget.TextView(context).apply {
            text = "${currentMax} Lux"
            textSize = 12f
            gravity = android.view.Gravity.END
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "$progress Lux"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        layout.addView(seekBar)

        val rangeRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        rangeRow.addView(minText)
        rangeRow.addView(maxText)
        layout.addView(rangeRow)

        val checkBox = android.widget.CheckBox(context).apply {
            text = getString(R.string.enable_fine_lux_control)
            isChecked = currentMax == LUX_MAX_FINE
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (12 * density).toInt()
            layoutParams = lp
        }
        checkBox.setOnCheckedChangeListener { _, isChecked ->
            val oldProgress = seekBar.progress
            currentMax = if (isChecked) LUX_MAX_FINE else LUX_MAX
            seekBar.max = currentMax
            seekBar.progress = oldProgress.coerceIn(0, currentMax)
            maxText.text = "${currentMax} Lux"
            label.text = "${seekBar.progress} Lux"
        }
        layout.addView(checkBox)

        if (currentReading.isNotEmpty()) {
            val readingLabel = android.widget.TextView(context).apply {
                text = currentReading
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                    setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (16 * density).toInt()
                layoutParams = lp
            }
            layout.addView(readingLabel)
        }

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                onConfirm(seekBar.progress)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    companion object {
        private const val LUX_MAX = 10000
        private const val LUX_MAX_FINE = 100
    }
}
