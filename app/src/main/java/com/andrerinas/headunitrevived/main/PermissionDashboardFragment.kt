package com.andrerinas.headunitrevived.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.main.permissions.DashboardState
import com.andrerinas.headunitrevived.main.permissions.PermissionCard
import com.andrerinas.headunitrevived.main.permissions.PermissionCardAdapter
import com.andrerinas.headunitrevived.main.permissions.PermissionRequest
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Every permission the head unit needs, on one screen, with the reason for each and its live state.
 *
 * Replaces the old first-run behaviour: eight system dialogs fired back to back from
 * `MainActivity.onCreate`, followed by an unlabelled battery settings screen, with nothing ever
 * reading the results. A denied Bluetooth permission meant Android Auto simply never connected and
 * there was nowhere to find that out.
 *
 * Two presentations, chosen by the [ARG_SETUP_MODE] argument:
 *  - setup (from [MainActivity]'s chain, whenever something is missing) — welcome header, Continue
 *    hands back to the setup chain so the disclaimer follows.
 *  - review (Settings › General) — toolbar with a back arrow, and the screen steps aside on its own
 *    if a projection session comes up while it is sitting open.
 *
 * Deliberately registers no back-press callback. [MainActivity] already owns one (it holds the
 * launcher-mode rule that Back must never finish the app), and a fragment-level callback would take
 * priority over it.
 */
class PermissionDashboardFragment : Fragment() {

    private val viewModel: PermissionDashboardViewModel by viewModels()

    private lateinit var actionButton: MaterialButton
    private lateinit var listView: RecyclerView
    private lateinit var adapter: PermissionCardAdapter

    private var setupMode = false

    /**
     * Set when we hand off to a system settings screen. MIUI — which both head units run — can keep
     * reporting the old value of `canDrawOverlays` for a moment after the switch is flipped, so the
     * plain `onResume` re-read is followed by a second one shortly after.
     */
    private var awaitingSpecialAccessResult = false

    /** Hoisted so re-emitted rows keep comparing equal under DiffUtil (see [PermissionCardAdapter]). */
    private val onCardClick: (PermissionCard) -> Unit = { card -> handleCardTap(card) }

    // Must be registered before STARTED, so it is a field initializer rather than onViewCreated work.
    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            AppLog.i("PERMISSIONS: result ${results.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }}")
            // Always a full re-read, never a targeted one: on Android 13+ the Bluetooth trio and
            // NEARBY_WIFI_DEVICES share a permission group, so granting one silently grants the
            // other and a second card changes without having been asked about.
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupMode = arguments?.getBoolean(ARG_SETUP_MODE, false) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_permission_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val appBar = view.findViewById<AppBarLayout>(R.id.permission_appbar)
        val header = view.findViewById<View>(R.id.permission_header)
        // The welcome header and the toolbar title say the same thing; on a 332dp-tall landscape
        // screen there is only room for one of them.
        appBar.visibility = if (setupMode) View.GONE else View.VISIBLE
        header.visibility = if (setupMode) View.VISIBLE else View.GONE

        view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { leave(markDismissed = false) }

        adapter = PermissionCardAdapter(onCardClick)
        listView = view.findViewById(R.id.permission_list)
        listView.adapter = adapter

        actionButton = view.findViewById(R.id.permission_action_button)
        actionButton.setOnClickListener {
            // Continue is the only way to say "I'm done here". Everything else — Back, the toolbar
            // arrow, a session taking over — means "later", and leaves the screen free to return.
            if (viewModel.state.value.allRequiredGranted) leave(markDismissed = true) else refresh()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }

        // Left open from Settings, this screen is protected against projection taking the foreground
        // (ScreenFocus treats every non-Home destination that way), so a session coming up would run
        // headless behind a permission list with nothing to say for itself. Step aside instead.
        // During first-run setup we stay put — the rider is mid-task and should not be interrupted.
        if (!setupMode) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    App.provide(requireContext()).commManager.connectionState.collect {
                        if (App.provide(requireContext()).commManager.isConnected) {
                            AppLog.i("PERMISSIONS: session came up, leaving the dashboard")
                            leave(markDismissed = false)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (awaitingSpecialAccessResult) {
            awaitingSpecialAccessResult = false
            view?.postDelayed({ if (isAdded) refresh() }, SPECIAL_ACCESS_RECHECK_MS)
        }
    }

    private fun refresh() {
        if (!isAdded) return
        viewModel.refresh { shouldShowRequestPermissionRationale(it) }
    }

    private fun render(state: DashboardState) {
        adapter.submitList(state.cards)

        val context = context ?: return
        val ready = state.allRequiredGranted
        actionButton.setText(if (ready) R.string.permission_continue else R.string.permission_refresh)
        // Gold once nothing blocks a connection; neutral while something does. Deliberately still
        // clickable in the neutral state — it is the manual re-check, which is the only way out
        // when a ROM reports a special-access grant late.
        actionButton.backgroundTintList = ContextCompat.getColorStateList(
            context,
            if (ready) R.color.brand_accent else R.color.m3_surface_container_high
        )
        actionButton.setTextColor(
            ContextCompat.getColor(
                context,
                if (ready) R.color.brand_on_accent else R.color.m3_on_surface_variant
            )
        )
    }

    private fun handleCardTap(card: PermissionCard) {
        when (val request = card.spec.request) {
            is PermissionRequest.Runtime -> {
                if (card.status == com.andrerinas.headunitrevived.main.permissions.PermissionStatus.PERMANENTLY_DENIED) {
                    openAppSettings()
                    return
                }
                val settings = Settings(requireContext())
                // Marked before launching, not in the result callback. If Android has already
                // decided to swallow the dialog the launch returns immediately with nothing to
                // observe; marking first means the next tap correctly routes to app settings, so
                // the worst case is one dead tap instead of a permanent dead end.
                request.permissions.forEach { settings.markPermissionAsked(it) }
                AppLog.i("PERMISSIONS: requesting ${card.spec.id}")
                runtimePermissionLauncher.launch(request.permissions.toTypedArray())
            }
            is PermissionRequest.SpecialAccess -> launchSpecialAccess(request)
        }
    }

    private fun launchSpecialAccess(request: PermissionRequest.SpecialAccess) {
        val packageUri = Uri.fromParts("package", requireContext().packageName, null)
        val primary = Intent(request.action).apply { if (request.withPackageUri) data = packageUri }
        awaitingSpecialAccessResult = true
        try {
            startActivity(primary)
        } catch (e: Exception) {
            // The battery exemption action is missing on some ROMs; fall back to the list screen,
            // which takes no package URI.
            AppLog.w("PERMISSIONS: ${request.action} unavailable (${e.message})")
            val fallback = request.fallbackAction
            if (fallback == null) {
                awaitingSpecialAccessResult = false
                toastSettingsUnavailable()
                return
            }
            try {
                startActivity(Intent(fallback))
            } catch (e2: Exception) {
                AppLog.e("PERMISSIONS: $fallback unavailable too (${e2.message})")
                awaitingSpecialAccessResult = false
                toastSettingsUnavailable()
            }
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().packageName, null)
                )
            )
        } catch (e: ActivityNotFoundException) {
            AppLog.e("PERMISSIONS: app details screen unavailable (${e.message})")
            toastSettingsUnavailable()
        }
    }

    private fun toastSettingsUnavailable() {
        if (isAdded) {
            Toast.makeText(requireContext(), R.string.permission_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Leaves the screen and, on first run, hands control back to the setup chain so the disclaimer
     * follows immediately — popping a fragment does not fire `MainActivity.onResume`, so without
     * this the next step would wait for an unrelated resume. The same handoff
     * [SafetyDisclaimerDialog] already uses.
     *
     * @param markDismissed true only for Continue. It stops the setup chain from offering the
     *        screen again for merely recommended gaps; a missing required permission reopens it
     *        every run regardless. Back is deliberately not routed through here at all, so backing
     *        out always reads as "later".
     */
    private fun leave(markDismissed: Boolean) {
        if (markDismissed) {
            Settings(requireContext()).hasDismissedPermissionSetup = true
        }
        val nav = findNavController()
        if (!nav.navigateUp()) {
            activity?.finish()
            return
        }
        (activity as? MainActivity)?.checkSetupFlow()
    }

    companion object {
        const val ARG_SETUP_MODE = "setupMode"
        private const val SPECIAL_ACCESS_RECHECK_MS = 600L
    }
}
