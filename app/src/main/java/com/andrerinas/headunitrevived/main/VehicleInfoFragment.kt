package com.andrerinas.headunitrevived.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.utils.Settings
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class VehicleInfoFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_vehicle_info, container, false)
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

        // No Save item: each row writes straight through.
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
        val scrollState = recyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        items.add(SettingItem.InfoBanner(
            stableId = "vehicleInfoRestartNote",
            textResId = R.string.vehicle_info_restart_note
        ))

        items.add(SettingItem.CategoryHeader("vehicle", R.string.category_vehicle))

        items.add(SettingItem.SettingEntry(
            stableId = "vehicleDisplayName",
            nameResId = R.string.vehicle_display_name_label,
            value = settings.vehicleDisplayName,
            onClick = {
                showTextInputDialog(R.string.vehicle_display_name_label, settings.vehicleDisplayName) { value ->
                    settings.vehicleDisplayName = value
                    settings.commit()
                    updateSettingsList()
                }
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "vehicleMake",
            nameResId = R.string.vehicle_make_label,
            value = settings.vehicleMake,
            onClick = {
                showTextInputDialog(R.string.vehicle_make_label, settings.vehicleMake) { value ->
                    settings.vehicleMake = value
                    settings.commit()
                    updateSettingsList()
                }
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "vehicleModel",
            nameResId = R.string.vehicle_model_label,
            value = settings.vehicleModel,
            onClick = {
                showTextInputDialog(R.string.vehicle_model_label, settings.vehicleModel) { value ->
                    settings.vehicleModel = value
                    settings.commit()
                    updateSettingsList()
                }
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "vehicleYear",
            nameResId = R.string.vehicle_year_label,
            value = settings.vehicleYear,
            onClick = {
                showYearInputDialog(settings.vehicleYear) { value ->
                    settings.vehicleYear = value
                    settings.commit()
                    updateSettingsList()
                }
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "rightHandDrive",
            nameResId = R.string.right_hand_drive,
            descriptionResId = R.string.right_hand_drive_description,
            isChecked = settings.rightHandDrive,
            onCheckedChanged = { isChecked ->
                settings.rightHandDrive = isChecked
                settings.commit()
            }
        ))
        settingsAdapter.submitList(items) {
            scrollState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    private fun showTextInputDialog(titleResId: Int, currentValue: String, onResult: (String) -> Unit) {
        showTextInputDialogWithMessage(titleResId, null, currentValue, onResult)
    }

    private fun showTextInputDialogWithMessage(titleResId: Int, messageResId: Int?, currentValue: String, onResult: (String) -> Unit) {
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }

        if (messageResId != null) {
            val messageText = android.widget.TextView(requireContext()).apply {
                setText(messageResId)
                val textColorAttr = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.textColorSecondary, textColorAttr, true)
                setTextColor(context.resources.getColor(textColorAttr.resourceId, context.theme))
                textSize = 13f
                setPadding(0, 0, 0, 24)
            }
            container.addView(messageText)
        }

        val editText = android.widget.EditText(requireContext()).apply {
            setText(currentValue)
            setSelection(text.length)
        }
        container.addView(editText)

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(titleResId)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = editText.text.toString().trim()
                if (value.isNotEmpty()) {
                    onResult(value)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        editText.requestFocus()
    }

    private fun showYearInputDialog(currentValue: String, onResult: (String) -> Unit) {
        val maxYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 2

        val editText = android.widget.EditText(requireContext()).apply {
            setText(currentValue)
            setSelection(text.length)
            setPadding(48, 32, 48, 16)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        }

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.vehicle_year_label)
            .setView(editText)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        editText.requestFocus()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = editText.text.toString().trim()
            val year = text.toIntOrNull()
            when {
                text.isEmpty() || year == null -> {
                    editText.error = getString(R.string.vehicle_year_error_invalid)
                }
                year > maxYear -> {
                    editText.error = getString(R.string.vehicle_year_error_max, maxYear)
                }
                year < 1900 -> {
                    editText.error = getString(R.string.vehicle_year_error_invalid)
                }
                else -> {
                    onResult(text)
                    dialog.dismiss()
                }
            }
        }
    }
}
