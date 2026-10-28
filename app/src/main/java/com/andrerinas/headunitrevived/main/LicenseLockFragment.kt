package com.andrerinas.headunitrevived.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.license.LicenseGuard
import com.andrerinas.headunitrevived.license.LicenseStatus
import com.andrerinas.headunitrevived.utils.AppLog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Shown by [MainActivity] in the Play build while the licence is not verified. Never in the GitHub
 * build, whose LicenseGuard is always licensed.
 *
 * Not a crash, and not a dead end: it says why, offers the one thing that fixes the common case
 * (getting online once), and steps aside by itself as soon as the licence verifies — including when
 * the verification finishes in the background because the rider connected to Wi-Fi.
 *
 * Back does nothing here. That rule lives in MainActivity's back callback, not in this fragment: a
 * fragment callback loses priority to the activity's every time the activity is restarted (back from
 * the Play Store, say), and Back then fell through to Home. MainActivity also brings this screen back
 * whenever Home is reached while the licence is not verified.
 */
class LicenseLockFragment : Fragment() {

    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var statusLine: TextView
    private lateinit var verifyButton: MaterialButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_license_lock, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        title = view.findViewById(R.id.license_title)
        body = view.findViewById(R.id.license_body)
        statusLine = view.findViewById(R.id.license_status)
        verifyButton = view.findViewById(R.id.license_verify)

        verifyButton.setOnClickListener {
            AppLog.i("LICENSE: verify requested from the lock screen")
            LicenseGuard.verifyNow(requireContext())
        }
        view.findViewById<MaterialButton>(R.id.license_wifi).setOnClickListener {
            open(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
        }
        view.findViewById<MaterialButton>(R.id.license_play).setOnClickListener {
            val pkg = requireContext().packageName
            if (!open(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))) {
                open(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LicenseGuard.status.combine(LicenseGuard.checking) { s, c -> s to c }
                    .collect { (status, checking) -> render(status, checking) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Wi-Fi settings or the Play Store: look again rather than wait for the next run.
        if (LicenseGuard.isUsable(requireContext())) leave()
    }

    private fun render(status: LicenseStatus, checking: Boolean) {
        if (status == LicenseStatus.LICENSED) {
            leave()
            return
        }
        val (titleRes, bodyRes) = when (status) {
            LicenseStatus.NOT_LICENSED -> R.string.license_lock_title_not_licensed to R.string.license_lock_body_not_licensed
            LicenseStatus.TAMPERED -> R.string.license_lock_title_tampered to R.string.license_lock_body_tampered
            else -> R.string.license_lock_title_unverified to R.string.license_lock_body_unverified
        }
        title.setText(titleRes)
        body.setText(bodyRes)
        statusLine.visibility = if (checking) View.VISIBLE else View.GONE
        statusLine.setText(R.string.license_lock_checking)
        // A re-signed APK cannot be fixed by asking again; only reinstalling from Play helps.
        verifyButton.visibility = if (status == LicenseStatus.TAMPERED) View.GONE else View.VISIBLE
        verifyButton.isEnabled = !checking
    }

    private fun leave() {
        val nav = findNavController()
        if (nav.currentDestination?.id != R.id.licenseLockFragment) return
        AppLog.i("LICENSE: licence verified, leaving the lock screen")
        nav.popBackStack()
        // Carry on with the setup chain (permissions, disclaimer) the lock screen interrupted.
        (activity as? MainActivity)?.checkSetupFlow()
    }

    private fun open(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
