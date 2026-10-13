package com.andrerinas.headunitrevived.connection

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.contract.BssidHelperProtocol.Sighting
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * The window during which the head unit will accept its own Wi-Fi address from the phone-side
 * helper.
 *
 * Deliberately outlives the setup screen, which the first version did not — and that was the bug.
 * The moment the phone reaches over Bluetooth, the phone's Android Auto decides a head unit has
 * turned up and starts a handshake; the head unit surfaces its connecting UI, that arrives with
 * FLAG_ACTIVITY_CLEAR_TOP, and the settings screen underneath is finished. The listener went with
 * it, so the helper found a closed door a second after the rider pressed Send. Tying the listener
 * to a screen only works if nothing else can take the screen away, and something can.
 *
 * What the rider asked for is still true: nothing here runs on its own. There is no service, no
 * boot hook, and until somebody opens the setup screen not one line of this executes. Opening it
 * arms a **[WINDOW_MS] window**; the window closes on its own, or when the rider walks back out of
 * the screen deliberately. Outside it there is no socket and no service record.
 */
object BssidHelperSession {

    /**
     * Long enough to walk to the phone, run a scan, pick a device and wait out a service-record
     * refresh, with room for a second attempt. Short enough that forgetting about it does not leave
     * anything running for the rest of the ride.
     */
    private const val WINDOW_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var server: BssidHelperServer? = null

    /** When the window closes, or 0 when nothing is armed. */
    @Volatile var expiresAt = 0L
        private set

    /** What the helper last told us, kept so the screen can show it after coming back. */
    @Volatile var lastResult: Result? = null
        private set

    /** Whether the phone's two routes agreed, once there is an answer to that. */
    @Volatile var crossCheck: CrossCheck? = null
        private set

    sealed class Result {
        data class Stored(val bssid: String) : Result()
        data class NoMatch(val reported: Int) : Result()
    }

    /**
     * Whether the two routes the phone looks by ended up at the same address.
     *
     * Worth keeping rather than only logging, because it settles something this project has assumed
     * for months without confirming: that a peer's Wi-Fi Direct *device* address is the same thing
     * as its group's BSSID. Nobody is going to pull a logcat to find that out.
     *
     * The first field run came back "only one route found us", which answers nothing — it does not
     * say whether the discovery route disagreed or was never in a position to be asked. So the
     * unmatched cases now carry why.
     */
    sealed class CrossCheck {
        object Agree : CrossCheck()
        data class Disagree(val other: String) : CrossCheck()

        /** The scan found us; discovery did not. [ownName] is null when we do not know our own. */
        data class DiscoveryUnmatched(
            val ownName: String?,
            val peerNames: List<String>
        ) : CrossCheck()

        /** Discovery found us but the scan did not — so there is nothing to check it against. */
        object ScanUnmatched : CrossCheck()
    }

    val isListening: Boolean get() = server?.isListening == true
    val isSocketOpen: Boolean get() = server?.isSocketOpen == true
    val failure: BssidHelperServer.Failure? get() = server?.failure
    val errorText: String? get() = server?.errorText
    val sessions: Int get() = server?.sessions ?: 0

    /** Seconds left in the window, floored at zero. */
    val secondsLeft: Long
        get() = ((expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)

    /** @return false if the listener could not be opened; see [failure] for why. */
    fun arm(context: Context): Boolean {
        val app = context.applicationContext
        expiresAt = System.currentTimeMillis() + WINDOW_MS
        handler.removeCallbacks(expiry)
        handler.postDelayed(expiry, WINDOW_MS)

        if (server?.isListening == true) return true

        lastResult = null
        crossCheck = null
        val listener = BssidHelperServer(app, scope) { sightings -> adopt(app, sightings) }
        server = listener
        val started = listener.start()
        if (!started) {
            expiresAt = 0L
            handler.removeCallbacks(expiry)
        }
        return started
    }

    fun disarm() {
        handler.removeCallbacks(expiry)
        expiresAt = 0L
        server?.stop()
        server = null
    }

    private val expiry = Runnable {
        if (server != null) AppLog.i("BssidHelper: listening window expired.")
        disarm()
    }

    /**
     * Tell the service that Settings.staticBSSID now holds a different address, so it can rebuild
     * the Wi-Fi Direct group once and start advertising it. Shared with the manual-entry path in
     * the setup screen, which leaves the value in exactly the same state.
     *
     * The service decides whether to act: it refuses while a session is live, so Wi-Fi Setup keeps
     * costing the AA connection nothing. It also suppresses the poke that a credential delivery
     * would normally trigger — writing the address is not the same as asking to connect.
     */
    fun notifyBssidChanged(context: Context) {
        AppLog.i("BssidSetup: Static BSSID written — asking the service to rebuild the group.")
        val intent = android.content.Intent(context.applicationContext, AapService::class.java).apply {
            action = AapService.ACTION_BSSID_CHANGED
        }
        try {
            androidx.core.content.ContextCompat.startForegroundService(context.applicationContext, intent)
        } catch (e: Exception) {
            AppLog.w("BssidSetup: could not reach the service to report the new BSSID: ${e.message}")
        }
    }

    /**
     * Called on the listener thread with everything the phone saw. Stores the address of whichever
     * sighting is ours, then asks the service to rebuild the group once so the new address is the
     * one we actually advertise — until some group event happens on its own, both the live group
     * and the handshake manager's cached BSSID still hold the value resolved before this delivery,
     * which on a fresh install is the masked one. Still no poke: the rider connects when ready.
     */
    private fun adopt(context: Context, sightings: List<Sighting>): BssidMatcher.Match? {
        val settings = App.provide(context).settings
        val ownSsid = AapService.wifiDirectSsid.value
        val ownName = AapService.wifiDirectName.value
        val result = BssidMatcher.match(sightings, ownSsid, ownName)

        val match = result.chosen
        if (match == null) {
            AppLog.w(
                "BssidSetup: none of the ${sightings.size} sightings matched " +
                    "(our SSID is ${ownSsid ?: "<unknown>"}, our P2P name is ${ownName ?: "<unknown>"})."
            )
            lastResult = Result.NoMatch(sightings.size)
            return null
        }

        val other = result.alternative
        crossCheck = when {
            other != null && other.bssid.equals(match.bssid, ignoreCase = true) -> {
                AppLog.i("BssidSetup: scan and P2P discovery agree on ${match.bssid}.")
                CrossCheck.Agree
            }
            other != null -> {
                AppLog.w("BssidSetup: scan says ${match.bssid} but P2P discovery says ${other.bssid} — using the scan.")
                CrossCheck.Disagree(other.bssid)
            }
            match.source == BssidMatcher.Source.P2P -> {
                AppLog.i("BssidSetup: only P2P discovery found us; the scan did not.")
                CrossCheck.ScanUnmatched
            }
            else -> {
                // The scan carried the day and discovery did not match. Say which of the two
                // reasons it was, because they lead somewhere completely different: a name we
                // never learned is our problem, a peer list that simply does not contain us is
                // the phone's.
                val peerNames = sightings.filterIsInstance<Sighting.Peer>().map { it.deviceName }
                AppLog.i(
                    "BssidSetup: discovery did not match. Our own P2P name is " +
                        "${ownName ?: "<unknown>"}; phone reported ${peerNames.size} peer(s): " +
                        peerNames.joinToString(", ").ifEmpty { "<none>" }
                )
                CrossCheck.DiscoveryUnmatched(ownName, peerNames)
            }
        }

        AppLog.i(
            "BssidSetup: adopting ${match.bssid} for '${match.label}' via ${match.source} " +
                "(was ${settings.staticBSSID ?: "<null>"})."
        )
        settings.staticBSSID = match.bssid
        lastResult = Result.Stored(match.bssid)
        notifyBssidChanged(context)

        // The setup screen is very likely gone by now — the connecting UI took it — so say so where
        // the rider will actually see it. Without this the handover succeeds invisibly.
        handler.post {
            Toast.makeText(
                context,
                context.getString(R.string.wifi_setup_helper_stored, match.bssid),
                Toast.LENGTH_LONG
            ).show()
        }
        return match
    }
}
