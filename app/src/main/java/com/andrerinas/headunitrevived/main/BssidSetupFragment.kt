package com.andrerinas.headunitrevived.main

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.connection.BssidHelperServer
import com.andrerinas.headunitrevived.connection.BssidHelperSession
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.DialogUtils
import com.andrerinas.headunitrevived.utils.Settings
import com.google.android.material.appbar.MaterialToolbar

/**
 * The one-off screen where the head unit is told its own Wi-Fi address.
 *
 * Android hides the MAC of our own Wi-Fi Direct interface from us (SDK 29 and up), and the Android
 * Auto handshake has to send it to the phone. Get it wrong and the phone simply never joins the
 * group — no error, anywhere. So the value has to come from outside: either from the phone-side
 * helper app, which can see us perfectly well and hands the address over Bluetooth, or typed in by
 * a rider reading it off any Wi-Fi analyser.
 *
 * The screen only *arms* the listener — [BssidHelperSession] owns it, and owns it for a fixed
 * window rather than for as long as this screen happens to be visible. That is not tidiness: the
 * phone reaching over Bluetooth makes its Android Auto start a handshake, the head unit brings its
 * connecting UI to the front with FLAG_ACTIVITY_CLEAR_TOP, and this screen is finished underneath.
 * A listener tied to it died a second after the rider pressed Send.
 *
 * Nothing here touches the handshake, the group, or the credentials. The only thing written is
 * [Settings.staticBSSID], read on the next group setup like any other setting.
 */
class BssidSetupFragment : Fragment() {

    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar

    private val ticker = Handler(Looper.getMainLooper())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_bssid_setup, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { leaveSetup() }
        })

        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { leaveSetup() }

        settingsAdapter = SettingsAdapter()
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = settingsAdapter

        updateSettingsList()
    }

    override fun onStart() {
        super.onStart()
        startListening()
        ticker.post(tick)
    }

    /**
     * Stops redrawing, and nothing else. The listener deliberately stays armed — this screen is
     * routinely closed *for* us by the connecting UI, which is exactly when the helper needs the
     * door open. Leaving on purpose goes through [leaveSetup].
     */
    override fun onStop() {
        super.onStop()
        ticker.removeCallbacks(tick)
    }

    /** Redraws the countdown once a second; the list is tiny and DiffUtil skips unchanged rows. */
    private val tick = object : Runnable {
        override fun run() {
            updateSettingsList()
            ticker.postDelayed(this, 1_000L)
        }
    }

    private fun startListening() {
        if (isAndroidAutoLive()) {
            AppLog.i("BssidSetup: Android Auto is connected — not opening the helper listener.")
            return
        }
        BssidHelperSession.arm(requireContext())
    }

    /** The rider walking out of the screen — the one exit that also closes the window. */
    private fun leaveSetup() {
        BssidHelperSession.disarm()
        navigateBack()
    }

    private fun isAndroidAutoLive(): Boolean =
        App.provide(requireContext()).commManager.isConnected

    // Held rather than written inline, so the rows this screen rebuilds every second compare equal
    // to the previous ones. A fresh lambda on each pass makes every row look changed to DiffUtil,
    // which rebinds the whole list once a second and leaves the window permanently non-idle —
    // enough to keep accessibility services (and uiautomator) waiting forever.
    private val onNothing: (String) -> Unit = { }
    private val onEditBssid: (String) -> Unit = { promptForBssid() }
    private val onListen: () -> Unit = { startListening(); updateSettingsList() }

    private fun updateSettingsList() {
        if (!isAdded) return
        val items = mutableListOf<SettingItem>()

        items.add(SettingItem.CategoryHeader("wifiSetup", R.string.category_wifi_setup))
        items.add(SettingItem.InfoBanner("wifiSetupIntro", R.string.wifi_setup_intro))

        items.add(SettingItem.SettingEntry(
            stableId = "wifiSetupSsid",
            nameResId = R.string.wifi_setup_network_name,
            value = AapService.wifiDirectSsid.value ?: getString(R.string.wifi_setup_network_none),
            onClick = onNothing
        ))

        val bssid = settings.staticBSSID
        items.add(SettingItem.SettingEntry(
            stableId = "wifiSetupBssid",
            nameResId = R.string.static_bssid_title,
            value = if (bssid.isNullOrEmpty() || bssid == "0") getString(R.string.auto) else bssid,
            onClick = onEditBssid
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "wifiSetupHelper",
            nameResId = R.string.wifi_setup_helper,
            value = helperStatus(),
            onClick = onNothing
        ))

        crossCheckText()?.let { text ->
            items.add(SettingItem.SettingEntry(
                stableId = "wifiSetupCrossCheck",
                nameResId = R.string.wifi_setup_cross_check,
                value = text,
                onClick = onNothing
            ))
        }

        if (!BssidHelperSession.isListening && !isAndroidAutoLive()) {
            items.add(SettingItem.ActionButton(
                stableId = "wifiSetupListen",
                textResId = R.string.wifi_setup_listen,
                onClick = onListen
            ))
        }

        settingsAdapter.submitList(items)
    }

    private fun promptForBssid() {
        val current = settings.staticBSSID
        DialogUtils.showTextInputDialog(
            requireContext(),
            R.string.static_bssid_enter_value,
            if (current == "0") "" else current
        ) { value ->
            val next = value.ifEmpty { "0" }
            val changed = !next.equals(current ?: "", ignoreCase = true)
            settings.staticBSSID = next
            updateSettingsList()
            // Same state the helper leaves behind, so it needs the same follow-up: the live group
            // still advertises the address resolved before this edit. Skipped when the rider
            // reopened the dialog and pressed OK on the value that was already there.
            if (changed) BssidHelperSession.notifyBssidChanged(requireContext())
        }
    }

    private fun crossCheckText(): String? = when (val check = BssidHelperSession.crossCheck) {
        BssidHelperSession.CrossCheck.Agree -> getString(R.string.wifi_setup_cross_check_agree)
        is BssidHelperSession.CrossCheck.Disagree ->
            getString(R.string.wifi_setup_cross_check_disagree, check.other)
        BssidHelperSession.CrossCheck.ScanUnmatched ->
            getString(R.string.wifi_setup_cross_check_scan_missed)
        is BssidHelperSession.CrossCheck.DiscoveryUnmatched -> {
            val seen = check.peerNames.joinToString(", ").ifEmpty { getString(R.string.wifi_setup_cross_check_no_peers) }
            if (check.ownName.isNullOrEmpty()) {
                getString(R.string.wifi_setup_cross_check_no_name, check.peerNames.size)
            } else {
                getString(R.string.wifi_setup_cross_check_no_peer_match, check.ownName, seen)
            }
        }
        null -> null
    }

    private fun helperStatus(): String {
        when (val outcome = BssidHelperSession.lastResult) {
            is BssidHelperSession.Result.Stored ->
                return getString(R.string.wifi_setup_helper_stored, outcome.bssid)
            is BssidHelperSession.Result.NoMatch ->
                return getString(R.string.wifi_setup_helper_no_match, outcome.reported)
            null -> {}
        }
        if (isAndroidAutoLive()) return getString(R.string.wifi_setup_helper_busy)

        if (BssidHelperSession.isListening) {
            // "Opening" is a real state, not pedantry: arming returns before the RFCOMM socket
            // exists, so a listener that threw on the way up used to read as "listening" — which is
            // exactly the ambiguity that cost us a field round.
            if (!BssidHelperSession.isSocketOpen) return getString(R.string.wifi_setup_helper_opening)
            val remaining = BssidHelperSession.secondsLeft
            val clock = String.format("%d:%02d", remaining / 60, remaining % 60)
            val waiting = getString(R.string.wifi_setup_helper_waiting, clock)
            val sessions = BssidHelperSession.sessions
            return if (sessions > 0) {
                "$waiting — ${getString(R.string.wifi_setup_helper_sessions, sessions)}"
            } else waiting
        }

        BssidHelperSession.errorText?.let { return it }

        return when (BssidHelperSession.failure) {
            BssidHelperServer.Failure.MISSING_PERMISSION -> getString(R.string.wifi_setup_helper_no_permission)
            BssidHelperServer.Failure.BLUETOOTH_OFF -> getString(R.string.wifi_setup_helper_bt_off)
            BssidHelperServer.Failure.SOCKET_ERROR -> getString(R.string.wifi_setup_helper_error)
            null -> getString(R.string.wifi_setup_helper_stopped)
        }
    }

    private fun navigateBack() {
        try {
            if (!findNavController().navigateUp()) requireActivity().finish()
        } catch (e: Exception) {
            requireActivity().finish()
        }
    }
}
