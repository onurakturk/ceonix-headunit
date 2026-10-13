package com.andrerinas.headunitrevived.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.utils.LauncherMode
import com.andrerinas.headunitrevived.utils.Settings
import com.google.android.material.appbar.MaterialToolbar

/**
 * Single-toggle sub-screen: "Use as launcher". Unlike the other settings screens this one
 * applies immediately (no Save button), because flipping it has side effects — it enables/
 * disables the HOME alias component and opens the system Home-app picker.
 */
class LauncherModeFragment : Fragment() {

    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_launcher_mode, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })

        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { navigateBack() }

        settingsAdapter = SettingsAdapter()
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = settingsAdapter

        updateSettingsList()
    }

    private fun navigateBack() {
        try {
            if (!findNavController().navigateUp()) {
                requireActivity().finish()
            }
        } catch (e: Exception) {
            requireActivity().finish()
        }
    }

    private fun updateSettingsList() {
        val items = mutableListOf<SettingItem>()

        items.add(SettingItem.CategoryHeader("launcherMode", R.string.category_launcher_mode))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "useAsLauncher",
            nameResId = R.string.use_as_launcher,
            descriptionResId = R.string.use_as_launcher_description,
            isChecked = settings.useAsLauncher,
            onCheckedChanged = { isChecked ->
                settings.useAsLauncher = isChecked
                LauncherMode.setEnabled(requireContext(), isChecked)
                // Send the user to the system Home-app picker so they can select this app
                // (when enabling) or their previous launcher (when disabling).
                LauncherMode.openHomeSettings(requireContext())
            }
        ))

        settingsAdapter.submitList(items)
    }
}
