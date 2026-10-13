package com.andrerinas.headunitrevived.main

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.main.settings.AutoConnectAdapter
import com.andrerinas.headunitrevived.main.settings.AutoConnectMethod
import com.andrerinas.headunitrevived.main.settings.AutoConnectTouchCallback
import com.andrerinas.headunitrevived.utils.Settings
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class AutoConnectFragment : Fragment() {

    private lateinit var settings: Settings
    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AutoConnectAdapter

    // Snapshot of initial state for change detection (visible methods only)
    private lateinit var initialOrder: List<String>
    private lateinit var initialEnabled: Map<String, Boolean>

    /**
     * Self Mode and single-USB auto-connect are not shown: this is a wireless-only head unit.
     * Their stored toggles are left untouched and [hiddenOrder] keeps their slots in the saved
     * priority order, so removing them from this set is all it takes to bring the rows back.
     */
    private val hiddenMethods = setOf(Settings.AUTO_CONNECT_SELF_MODE, Settings.AUTO_CONNECT_SINGLE_USB)
    private var hiddenOrder: List<String> = emptyList()


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_auto_connect, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        // Snapshot initial state (hidden methods are kept aside, not shown and not compared)
        val storedOrder = settings.autoConnectPriorityOrder.toList()
        hiddenOrder = storedOrder.filter { it in hiddenMethods }
        initialOrder = storedOrder.filterNot { it in hiddenMethods }
        initialEnabled = mapOf(
            Settings.AUTO_CONNECT_LAST_SESSION to settings.autoConnectLastSession,
            Settings.AUTO_CONNECT_SELF_MODE to settings.autoStartSelfMode,
            Settings.AUTO_CONNECT_SINGLE_USB to settings.autoConnectSingleUsbDevice
        ).filterKeys { it !in hiddenMethods }

        // Build method list in priority order
        val methods = initialOrder.mapNotNull { id ->
            methodDefinition(id)?.let { (nameRes, descRes) ->
                AutoConnectMethod(id, nameRes, descRes, initialEnabled[id] ?: false)
            }
        }.toMutableList()

        // The adapter calls this on every reorder and every toggle, which is exactly when the
        // new priority should be stored — there is no Save step any more.
        adapter = AutoConnectAdapter(methods) { persistCurrentState() }

        val touchCallback = AutoConnectTouchCallback(adapter)
        val itemTouchHelper = ItemTouchHelper(touchCallback)
        adapter.itemTouchHelper = itemTouchHelper

        toolbar = view.findViewById(R.id.toolbar)
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        itemTouchHelper.attachToRecyclerView(recyclerView)

        setupToolbar()

        // Intercept system back button
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })
    }

    private fun methodDefinition(id: String): Pair<Int, Int>? {
        return when (id) {
            Settings.AUTO_CONNECT_LAST_SESSION -> R.string.auto_connect_last_session to R.string.auto_connect_last_session_description
            Settings.AUTO_CONNECT_SELF_MODE -> R.string.auto_start_self_mode to R.string.auto_start_self_mode_description
            Settings.AUTO_CONNECT_SINGLE_USB -> R.string.auto_connect_single_usb to R.string.auto_connect_single_usb_description
            else -> null
        }
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            handleBackPress()
        }

        // No Save item: reordering or toggling a row stores it (see persistCurrentState).
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

    private fun persistCurrentState() {
        val orderedIds = adapter.getOrderedIds()
        val enabledStates = adapter.getEnabledStates()

        // Persist order, keeping the hidden methods' slots at the end
        settings.autoConnectPriorityOrder = orderedIds + hiddenOrder

        // Persist individual toggles
        enabledStates[Settings.AUTO_CONNECT_LAST_SESSION]?.let { settings.autoConnectLastSession = it }
        enabledStates[Settings.AUTO_CONNECT_SELF_MODE]?.let { settings.autoStartSelfMode = it }
        enabledStates[Settings.AUTO_CONNECT_SINGLE_USB]?.let { settings.autoConnectSingleUsbDevice = it }

        settings.commit()
    }
}
