package com.andrerinas.headunitrevived.main

import android.os.Bundle
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
import com.andrerinas.headunitrevived.utils.Settings
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

class MicSettingsFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_mic_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        // Intercept back press
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
        // No Save item: each row writes straight through (see applyChange).
    }

    private fun handleBackPress() {
        findNavController().navigateUp()
    }

    /**
     * The mic settings are read once, when the MicRecorder session is built, so a change can only
     * reach Android Auto on the next connection — this is the teardown the Save button used to do.
     */
    private fun applyChange() {
        settings.commit()
        val context = context ?: return
        if (!App.provide(context).commManager.isConnected) return

        Toast.makeText(context, getString(R.string.settings_session_restart_notice), Toast.LENGTH_LONG).show()
        ContextCompat.startForegroundService(context, Intent(context, AapService::class.java).apply {
            action = AapService.ACTION_STOP_SERVICE
        })
    }

    private fun updateSettingsList() {
        val scrollState = recyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        items.add(SettingItem.CategoryHeader("micSettings", R.string.microphone_settings))

        // Mic Sample Rate
        items.add(SettingItem.SettingEntry(
            stableId = "micSampleRate",
            nameResId = R.string.mic_sample_rate,
            value = "${settings.micSampleRate} Hz",
            onClick = { _ ->
                val nextRate = Settings.getNextMicSampleRate(settings.micSampleRate)
                settings.micSampleRate = nextRate
                applyChange()
                updateSettingsList()
            }
        ))

        // Mic Input Source
        val inputSourceTitles = resources.getStringArray(R.array.mic_input_sources)
        items.add(SettingItem.SettingEntry(
            stableId = "micInputSource",
            nameResId = R.string.mic_input_source,
            value = inputSourceTitles.getOrElse(settings.micInputSource) { getString(R.string.not_set) },
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.mic_input_source)
                    .setSingleChoiceItems(inputSourceTitles, settings.micInputSource) { dialog, which ->
                        settings.micInputSource = which
                        applyChange()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // Echo Canceler
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "micEchoCanceler",
            nameResId = R.string.mic_echo_canceler,
            descriptionResId = if (AcousticEchoCanceler.isAvailable())
                R.string.mic_echo_canceler_description else R.string.mic_feature_unsupported,
            isChecked = settings.micEchoCanceler ?:false,
            isEnabled = AcousticEchoCanceler.isAvailable(),
            onCheckedChanged = { isChecked ->
                settings.micEchoCanceler = isChecked
                applyChange()
                updateSettingsList()
            }
        ))

        // Noise Suppressor
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "micNoiseSuppressor",
            nameResId = R.string.mic_noise_suppressor,
            descriptionResId = if (NoiseSuppressor.isAvailable())
                R.string.mic_noise_suppressor_description else R.string.mic_feature_unsupported,
            isChecked = settings.micNoiseSuppressor ?:false,
            isEnabled = NoiseSuppressor.isAvailable(),
            onCheckedChanged = { isChecked ->
                settings.micNoiseSuppressor = isChecked
                applyChange()
                updateSettingsList()
            }
        ))

        // Auto Gain Control
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "micAutoGainControl",
            nameResId = R.string.mic_auto_gain_control,
            descriptionResId = if (AutomaticGainControl.isAvailable())
                R.string.mic_auto_gain_control_description else R.string.mic_feature_unsupported,
            isChecked = settings.micAutoGainControl ?:false,
            isEnabled = AutomaticGainControl.isAvailable(),
            onCheckedChanged = { isChecked ->
                settings.micAutoGainControl = isChecked
                applyChange()
                updateSettingsList()
            }
        ))

        settingsAdapter.submitList(items) {
            scrollState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }
}
