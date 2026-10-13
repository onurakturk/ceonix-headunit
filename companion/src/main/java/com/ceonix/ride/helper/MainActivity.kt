package com.ceonix.ride.helper

import android.Manifest
import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.andrerinas.headunitrevived.contract.BssidHelperProtocol
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * One screen, run once. No service, no boot hook, nothing left behind — the rider can uninstall
 * this the moment the head unit connects.
 *
 * Two steps, deliberately separate: find the address, then hand it over. Both keep a full record of
 * what they actually did, because both have already failed once in the field in a way that a
 * one-line "it did not work" could not explain — but that record now lives folded away under
 * Details, because it is for the bad day, not for the normal one.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var action: MaterialButton
    private lateinit var sendButton: MaterialButton
    private lateinit var sendAnyway: MaterialButton
    private lateinit var sendAnywayNote: TextView
    private lateinit var busyRow: View

    private lateinit var resultCard: com.google.android.material.card.MaterialCardView
    private lateinit var resultIcon: ImageView
    private lateinit var resultTitle: TextView
    private lateinit var resultText: TextView
    private lateinit var candidateBox: LinearLayout
    private lateinit var labelNetwork: TextView
    private lateinit var candidateSsid: TextView
    private lateinit var labelAddress: TextView
    private lateinit var candidateBssid: TextView
    private lateinit var copyButton: MaterialButton
    private lateinit var checklist: TextView
    private lateinit var nearMiss: TextView

    private lateinit var content: LinearLayout
    private lateinit var detailsCard: View
    private lateinit var detailsHeader: View
    private lateinit var detailsChevron: ImageView
    private lateinit var detailsBody: View
    private lateinit var report: TextView
    private lateinit var copyReportButton: MaterialButton

    private var findings: BssidScanner.Findings? = null
    private var searchReport: String = ""
    private var detailsOpen = false

    /**
     * What the last search concluded. Kept apart from [status], which carries the running
     * commentary — working, sending, blockers — while this drives the verdict card and the buttons.
     */
    private sealed class Outcome {
        object None : Outcome()

        /** A `DIRECT-…CEONIX` network: the one thing this app is looking for. */
        data class Found(val ap: BssidHelperProtocol.Sighting.AccessPoint) : Outcome()

        /**
         * No such network. [canSendAnyway] is true when there is still something to hand over: the
         * head unit does the real matching and can recognise itself in a Wi-Fi Direct peer that this
         * phone can only see as a device name.
         */
        data class NotFound(
            val canSendAnyway: Boolean,
            val nearMiss: BssidHelperProtocol.Sighting.AccessPoint?
        ) : Outcome()

        /** The head unit took it. */
        data class Handed(val bssid: String) : Outcome()
    }

    private var outcome: Outcome = Outcome.None

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) look() else setStatus(getString(R.string.error_permission))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        action = findViewById(R.id.action)
        sendButton = findViewById(R.id.send)
        sendAnyway = findViewById(R.id.send_anyway)
        sendAnywayNote = findViewById(R.id.send_anyway_note)
        busyRow = findViewById(R.id.busy_row)

        resultCard = findViewById(R.id.result_card)
        resultIcon = findViewById(R.id.result_icon)
        resultTitle = findViewById(R.id.result_title)
        resultText = findViewById(R.id.result_text)
        candidateBox = findViewById(R.id.candidate_box)
        labelNetwork = findViewById(R.id.label_network)
        candidateSsid = findViewById(R.id.candidate_ssid)
        labelAddress = findViewById(R.id.label_address)
        candidateBssid = findViewById(R.id.candidate_bssid)
        copyButton = findViewById(R.id.copy)
        checklist = findViewById(R.id.checklist)
        nearMiss = findViewById(R.id.near_miss)

        content = findViewById(R.id.content)
        detailsCard = findViewById(R.id.details_card)
        detailsHeader = findViewById(R.id.details_header)
        detailsChevron = findViewById(R.id.details_chevron)
        detailsBody = findViewById(R.id.details_body)
        report = findViewById(R.id.report)
        copyReportButton = findViewById(R.id.copy_report)

        // Cards appearing and the Details section unfolding both change the height of the column;
        // letting the framework animate it keeps the screen from jumping.
        content.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
        }

        checklist.text = listOf(R.string.check_power, R.string.check_screen, R.string.check_auto)
            .joinToString("\n") { "•  " + getString(it) }

        setStatus(getString(R.string.intro))
        render()

        action.setOnClickListener { requestPermissionsThenLook() }
        sendButton.setOnClickListener { pickDeviceThenSend() }
        sendAnyway.setOnClickListener { pickDeviceThenSend() }
        copyButton.setOnClickListener { copy(candidateBssid.text.toString()) }
        copyReportButton.setOnClickListener { copy(report.text.toString()) }
        detailsHeader.setOnClickListener { toggleDetails() }
    }

    private fun requestPermissionsThenLook() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    // ---------------------------------------------------------------- step 1: find

    private fun look() {
        val scanner = BssidScanner(this)
        scanner.findBlocker()?.let { setStatus(getString(blockerMessage(it))); return }

        outcome = Outcome.None
        render()
        setBusy(true)
        setStatus(getString(R.string.working))
        lifecycleScope.launch {
            val found = scanner.run()
            findings = found
            searchReport = buildSearchReport(found)
            showReport(searchReport)
            setBusy(false)
            action.setText(R.string.action_retry)

            // The old test here was `found.isEmpty`, which is only true when the phone saw
            // literally nothing — so a neighbour's router counted as success and the screen said
            // "Found it." over an empty address box. We are looking for one specific network, not
            // for any network.
            outcome = found.headUnitAccessPoints.firstOrNull()
                ?.let { Outcome.Found(it) }
                ?: Outcome.NotFound(
                    canSendAnyway = !found.isEmpty,
                    nearMiss = found.directAccessPoints.firstOrNull()
                )
            render()
            setStatus(getString(R.string.intro))
        }
    }

    // ---------------------------------------------------------------- step 2: hand over

    @SuppressLint("MissingPermission")
    private fun pickDeviceThenSend() {
        val sightings = findings?.sightings ?: return
        val reporter = BssidReporter(this)
        val devices = reporter.bondedDevices()
        if (devices.isEmpty()) {
            setStatus(getString(R.string.no_bonded_devices))
            return
        }

        // Asking beats guessing. The first version walked every paired device in turn, which meant
        // waiting out a failed connect to a watch and a pair of earbuds before it even reached the
        // head unit — and told us nothing about which one had refused.
        val names = devices.map { device ->
            val name = try { device.name } catch (e: SecurityException) { null }
            "${name ?: "?"}  (${device.address})"
        }
        // The theme is passed explicitly as well as declared: AppCompat resolves the unprefixed
        // alertDialogTheme attribute, and getting that wrong shows up as a stock purple dialog.
        AlertDialog.Builder(this, R.style.Theme_CEONIX_Helper_Dialog)
            .setTitle(R.string.pick_device)
            .setItems(names.toTypedArray()) { _, which -> send(devices[which], sightings) }
            .show()
    }

    private fun send(device: BluetoothDevice, sightings: List<BssidHelperProtocol.Sighting>) {
        setBusy(true)
        setStatus(getString(R.string.sending))
        lifecycleScope.launch {
            val report = BssidReporter(this@MainActivity)
                .send(device, sightings) { step ->
                    // Each attempt can block for tens of seconds. Naming the one in flight is the
                    // difference between "working" and "hung" to whoever is holding the phone.
                    setStatus(getString(R.string.sending_step, step))
                }
            setBusy(false)
            showReport(searchReport + "\n" + buildHandoverReport(report.steps))
            setStatus(
                when (val result = report.result) {
                    is BssidReporter.Result.Stored -> {
                        // The verdict card has to follow. Without this, a handover from the
                        // send-anyway path leaves "Could not see the head unit" on screen directly
                        // above "Done" — a flat contradiction.
                        outcome = Outcome.Handed(result.bssid)
                        render()
                        getString(R.string.stored, result.bssid)
                    }
                    // Everything below leaves the card alone: its verdict is still true.
                    is BssidReporter.Result.NoMatch -> getString(R.string.stored_none)
                    is BssidReporter.Result.Failed -> when (result.reason) {
                        BssidReporter.Reason.MISSING_BLUETOOTH_PERMISSION ->
                            getString(R.string.error_permission)
                        BssidReporter.Reason.BLUETOOTH_OFF ->
                            getString(R.string.error_bluetooth_off)
                        BssidReporter.Reason.NO_LISTENER ->
                            getString(R.string.error_no_listener)
                        BssidReporter.Reason.CONNECTION_FAILED ->
                            getString(R.string.error_connection)
                    }
                }
            )
        }
    }

    // ---------------------------------------------------------------- the screen

    private fun render() {
        when (val current = outcome) {
            Outcome.None -> {
                resultCard.visibility = View.GONE
                sendButton.visibility = View.GONE
                hideSendAnyway()
                applyFilled(action)
            }

            is Outcome.Found -> {
                paintSuccess(R.string.found_heading, R.string.found_body)
                showAddress(current.ap.ssid, current.ap.bssid)
                checklist.visibility = View.GONE
                nearMiss.visibility = View.GONE
                sendButton.visibility = View.VISIBLE
                hideSendAnyway()
                // Search again steps back: the rider's next move is to hand the address over.
                applyTonal(action)
            }

            is Outcome.NotFound -> {
                paint(
                    R.color.m3_surface_container_high, R.color.m3_on_surface, R.color.m3_error,
                    R.drawable.ic_m3_alert, R.string.not_found_heading, R.string.not_found_body
                )
                candidateBox.visibility = View.GONE
                checklist.visibility = View.VISIBLE
                current.nearMiss?.let {
                    nearMiss.text = getString(R.string.near_miss, it.ssid, it.bssid)
                    nearMiss.visibility = View.VISIBLE
                } ?: run { nearMiss.visibility = View.GONE }
                sendButton.visibility = View.GONE
                applyFilled(action)
                val show = if (current.canSendAnyway) View.VISIBLE else View.GONE
                sendAnyway.visibility = show
                sendAnywayNote.visibility = show
            }

            is Outcome.Handed -> {
                paintSuccess(R.string.handed_heading, R.string.handed_body)
                showAddress(null, current.bssid)
                checklist.visibility = View.GONE
                nearMiss.visibility = View.GONE
                sendButton.visibility = View.GONE
                hideSendAnyway()
                applyFilled(action)
            }
        }
    }

    private fun paintSuccess(@StringRes title: Int, @StringRes body: Int) = paint(
        R.color.m3_primary_cont, R.color.m3_on_primary_cont, R.color.m3_on_primary_cont,
        R.drawable.ic_m3_check, title, body
    )

    /**
     * One card, three verdicts. Everything inside takes the container's on-colour; the quieter
     * lines are dimmed in the layout rather than given a second colour, because the gold container
     * has no on-surface-variant of its own.
     */
    private fun paint(
        @ColorRes background: Int,
        @ColorRes onBackground: Int,
        @ColorRes iconTint: Int,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes body: Int
    ) {
        resultCard.setCardBackgroundColor(color(background))
        resultIcon.setImageResource(icon)
        ImageViewCompat.setImageTintList(resultIcon, ColorStateList.valueOf(color(iconTint)))

        val on = color(onBackground)
        listOf(
            resultTitle, resultText, labelNetwork, candidateSsid, labelAddress, candidateBssid,
            checklist, nearMiss
        ).forEach { it.setTextColor(on) }
        copyButton.setTextColor(on)
        copyButton.iconTint = ColorStateList.valueOf(on)

        resultTitle.setText(title)
        resultText.setText(body)
        resultCard.visibility = View.VISIBLE
    }

    private fun showAddress(ssid: String?, bssid: String) {
        candidateBox.visibility = View.VISIBLE
        // A handed-over address has no network name worth repeating — it is the head unit's own.
        labelNetwork.visibility = if (ssid == null) View.GONE else View.VISIBLE
        candidateSsid.visibility = if (ssid == null) View.GONE else View.VISIBLE
        candidateSsid.text = ssid.orEmpty()
        candidateBssid.text = bssid
    }

    private fun hideSendAnyway() {
        sendAnyway.visibility = View.GONE
        sendAnywayNote.visibility = View.GONE
    }

    private fun applyFilled(button: MaterialButton) {
        button.backgroundTintList = ColorStateList.valueOf(color(R.color.m3_primary))
        button.setTextColor(color(R.color.m3_on_primary))
        button.iconTint = ColorStateList.valueOf(color(R.color.m3_on_primary))
    }

    private fun applyTonal(button: MaterialButton) {
        button.backgroundTintList = ColorStateList.valueOf(color(R.color.m3_surface_container_high))
        button.setTextColor(color(R.color.m3_on_surface))
        button.iconTint = ColorStateList.valueOf(color(R.color.m3_on_surface))
    }

    private fun toggleDetails() {
        detailsOpen = !detailsOpen
        detailsBody.visibility = if (detailsOpen) View.VISIBLE else View.GONE
        detailsChevron.animate().rotation(if (detailsOpen) 90f else 0f).setDuration(150).start()
    }

    private fun color(@ColorRes id: Int) = ContextCompat.getColor(this, id)

    // ---------------------------------------------------------------- report

    private fun buildSearchReport(findings: BssidScanner.Findings): String {
        val text = StringBuilder()

        val freshness = getString(
            if (findings.scanWasFresh) R.string.report_scan_fresh else R.string.report_scan_cached
        )
        text.append(getString(R.string.report_scan_heading, findings.accessPoints.size, freshness))
            .append('\n')
        if (findings.accessPoints.isEmpty()) {
            text.append("  ").append(getString(R.string.report_nothing)).append('\n')
        } else {
            // Ours first if it is here, then the rest of the DIRECT-* crowd, then everything else.
            findings.accessPoints
                .sortedByDescending { ap ->
                    when {
                        BssidHelperProtocol.isLikelyHeadUnitSsid(ap.ssid) -> 2
                        ap.ssid.startsWith(BssidHelperProtocol.DIRECT_PREFIX) -> 1
                        else -> 0
                    }
                }
                .forEach { ap ->
                    val mhz = findings.frequencies[ap.bssid]
                    text.append("  ").append(ap.bssid).append("  ")
                        .append(if (ap.ssid.isEmpty()) "<hidden>" else ap.ssid)
                    if (mhz != null) text.append("  ").append(mhz).append(" MHz")
                    text.append('\n')
                }
        }
        if (findings.scanNote.isNotEmpty()) text.append("  ").append(findings.scanNote).append('\n')

        text.append('\n')
        text.append(getString(R.string.report_peers_heading, findings.peers.size)).append('\n')
        if (findings.peers.isEmpty()) {
            text.append("  ").append(getString(R.string.report_nothing)).append('\n')
        } else {
            findings.peers.forEach { peer ->
                text.append("  ").append(peer.address).append("  ").append(peer.deviceName)
                if (peer.isGroupOwner) text.append("  [group owner]")
                text.append('\n')
            }
        }
        if (findings.peerNote.isNotEmpty()) text.append("  ").append(findings.peerNote).append('\n')

        return text.toString()
    }

    private fun buildHandoverReport(steps: List<BssidReporter.Step>): String {
        val text = StringBuilder(getString(R.string.report_handover_heading)).append('\n')
        if (steps.isEmpty()) text.append("  ").append(getString(R.string.report_nothing)).append('\n')
        steps.forEach { step ->
            text.append("  ").append(if (step.ok) "OK  " else "--  ")
                .append(step.name).append(": ").append(step.detail).append('\n')
        }
        return text.toString()
    }

    /** Fills the dump and reveals its card, still folded. Whoever needs it knows to open it. */
    private fun showReport(text: String) {
        report.text = text
        detailsCard.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- odds and ends

    private fun blockerMessage(blocker: BssidScanner.Blocker): Int = when (blocker) {
        BssidScanner.Blocker.MISSING_LOCATION_PERMISSION,
        BssidScanner.Blocker.MISSING_NEARBY_PERMISSION -> R.string.error_permission
        BssidScanner.Blocker.LOCATION_SERVICES_OFF -> R.string.error_location_off
        BssidScanner.Blocker.WIFI_OFF -> R.string.error_wifi_off
    }

    private fun copy(value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BSSID", value))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    private fun setStatus(html: String) {
        status.text =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
            else Html.fromHtml(html)
    }

    private fun setBusy(busy: Boolean) {
        busyRow.visibility = if (busy) View.VISIBLE else View.GONE
        action.isEnabled = !busy
        sendButton.isEnabled = !busy
        sendAnyway.isEnabled = !busy
    }
}
