package com.ceonix.ride.helper

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.contract.BssidHelperProtocol
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Looks for the head unit's Wi-Fi Direct group, by both of the routes a phone has.
 *
 * Neither route is trusted: the first attempt at this app used a plain Wi-Fi scan and came back
 * with nothing at all on the rider's phone, while a third-party analyser on the same phone was
 * showing the network — and we never established why. So both routes run, and everything they
 * return is reported verbatim, including networks that obviously are not ours. Being able to see
 * what the phone actually saw is the point; picking the right one out of it is the head unit's job.
 */
class BssidScanner(private val context: Context) {

    /** Everything a single run turned up, plus why a route came back empty. */
    data class Findings(
        val accessPoints: List<BssidHelperProtocol.Sighting.AccessPoint>,
        val peers: List<BssidHelperProtocol.Sighting.Peer>,
        /** Frequency in MHz per scanned network, for the diagnostic list. Keyed by BSSID. */
        val frequencies: Map<String, Int>,
        /** True when the scan results came from a sweep we asked for, not the system's cache. */
        val scanWasFresh: Boolean,
        val scanNote: String,
        val peerNote: String
    ) {
        val sightings: List<BssidHelperProtocol.Sighting> get() = accessPoints + peers
        val isEmpty: Boolean get() = accessPoints.isEmpty() && peers.isEmpty()

        /** The `DIRECT-*` networks, which is where the head unit will almost certainly be. */
        val directAccessPoints: List<BssidHelperProtocol.Sighting.AccessPoint>
            get() = accessPoints.filter { it.ssid.startsWith(BssidHelperProtocol.DIRECT_PREFIX) }

        /**
         * The networks that name themselves as ours: `DIRECT-…CEONIX`. This is the one thing this app
         * is actually looking for, and is not the same question as [isEmpty] — which only asks
         * whether the phone saw anything at all, neighbours included.
         *
         * Only the Wi-Fi scan can produce these. Wi-Fi Direct discovery reports the head unit's
         * framework device name ("Mi 9T"), which only the head unit itself can recognise — so an
         * empty list here does not mean the head unit is absent, only that this phone did not see
         * its network name.
         */
        val headUnitAccessPoints: List<BssidHelperProtocol.Sighting.AccessPoint>
            get() = accessPoints.filter { BssidHelperProtocol.isLikelyHeadUnitSsid(it.ssid) }

        /** True when the Wi-Fi scan actually saw the head unit's own network. */
        val hasHeadUnit: Boolean get() = headUnitAccessPoints.isNotEmpty()
    }

    enum class Blocker {
        MISSING_LOCATION_PERMISSION,
        MISSING_NEARBY_PERMISSION,
        LOCATION_SERVICES_OFF,
        WIFI_OFF
    }

    /**
     * Everything that would make both routes come back empty for a reason that has nothing to do
     * with the head unit. Checked up front because the failure is otherwise indistinguishable from
     * "the head unit is not broadcasting", which sends the rider looking in the wrong place.
     */
    fun findBlocker(): Blocker? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return Blocker.MISSING_LOCATION_PERMISSION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED
        ) return Blocker.MISSING_NEARBY_PERMISSION

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locationOn = try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) { false }
        if (!locationOn) return Blocker.LOCATION_SERVICES_OFF

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) return Blocker.WIFI_OFF

        return null
    }

    suspend fun run(): Findings = withContext(Dispatchers.IO) {
        val scan = scanWifi()
        val peers = discoverPeers()
        Findings(
            accessPoints = scan.first,
            peers = peers.first,
            frequencies = scan.second,
            scanWasFresh = scan.third,
            scanNote = if (scan.first.isEmpty()) "No networks returned." else "",
            peerNote = peers.second
        )
    }

    /**
     * Route A: an ordinary Wi-Fi scan.
     *
     * Three things the first version of this got wrong, all of which produce "no networks" with no
     * hint that anything went wrong. `startScan` is throttled to a handful of calls per couple of
     * minutes, and returns false rather than throwing when it refuses; the results broadcast fires
     * for *any* scan, including one another app started before our group existed; and one sweep can
     * miss a channel. So: retry, insist on a sweep that actually updated the results, and say so
     * when we end up reading the cache anyway.
     *
     * @return scanned networks, their frequencies by BSSID, and whether the results were fresh.
     */
    @SuppressLint("MissingPermission")
    private suspend fun scanWifi():
        Triple<List<BssidHelperProtocol.Sighting.AccessPoint>, Map<String, Int>, Boolean> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        var fresh = false
        for (attempt in 1..SCAN_SWEEPS) {
            fresh = withTimeoutOrNull(SCAN_SWEEP_TIMEOUT_MS) { awaitFreshScan(wifi) } ?: false
            if (fresh) break
        }

        val results = try { wifi.scanResults } catch (e: Exception) { emptyList() }
        val networks = mutableListOf<BssidHelperProtocol.Sighting.AccessPoint>()
        val frequencies = mutableMapOf<String, Int>()
        for (result in results) {
            val bssid = result.BSSID ?: continue
            @Suppress("DEPRECATION")
            val ssid = result.SSID ?: ""
            networks.add(BssidHelperProtocol.Sighting.AccessPoint(ssid, bssid))
            frequencies[bssid] = result.frequency
        }
        return Triple(networks, frequencies, fresh)
    }

    /** Asks for one sweep and waits for a results broadcast that actually carries new data. */
    @SuppressLint("MissingPermission")
    private suspend fun awaitFreshScan(wifi: WifiManager): Boolean =
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    // Pre-M builds have no updated flag; there, any broadcast is as good as it gets.
                    val updated = intent.getBooleanExtra(
                        WifiManager.EXTRA_RESULTS_UPDATED, Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    )
                    if (!updated) return
                    try { context.unregisterReceiver(this) } catch (e: Exception) {}
                    if (cont.isActive) cont.resume(true)
                }
            }
            ContextCompat.registerReceiver(
                context, receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_EXPORTED
            )
            cont.invokeOnCancellation {
                try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
            }
            @Suppress("DEPRECATION")
            val accepted = try { wifi.startScan() } catch (e: Exception) { false }
            if (!accepted) {
                // Throttled. Let the timeout run: the system scans on its own schedule and the
                // broadcast we are waiting on may still arrive.
            }
        }

    /**
     * Route B: Wi-Fi Direct discovery.
     *
     * Never tried before, and it sidesteps both of the things that can silence route A: peer
     * addresses are not masked (only a device's own address is) and P2P discovery is not subject to
     * Wi-Fi scan throttling. What it gives back is the *device* address though, which is not
     * guaranteed to be the group's BSSID — that is for the head unit to compare.
     *
     * Discovery is always stopped again. Leaving it running would have the phone hopping channels
     * long after this screen is gone, which is exactly the kind of interference this app must not
     * cause.
     */
    @SuppressLint("MissingPermission")
    private suspend fun discoverPeers(): Pair<List<BssidHelperProtocol.Sighting.Peer>, String> {
        val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: return emptyList<BssidHelperProtocol.Sighting.Peer>() to "Wi-Fi Direct unavailable."
        val channel = manager.initialize(context, Looper.getMainLooper(), null)
            ?: return emptyList<BssidHelperProtocol.Sighting.Peer>() to "Wi-Fi Direct unavailable."

        val started = suspendCancellableCoroutine { cont ->
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (cont.isActive) cont.resume(null) }
                override fun onFailure(reason: Int) {
                    if (cont.isActive) cont.resume(p2pError(reason))
                }
            })
        }
        if (started != null) {
            stopDiscovery(manager, channel)
            return emptyList<BssidHelperProtocol.Sighting.Peer>() to "Discovery refused: $started"
        }

        // Peers trickle in, so there is nothing to wait on but the clock.
        //
        // Helper 1.2 cut this short once the list stopped growing, to save about twelve seconds.
        // The handover started failing in the field that same version, and this was the only thing
        // that changed in it — so it goes back. Twelve seconds is not worth spending a working
        // feature on, and leaving discovery early means the Wi-Fi side may still be hopping
        // channels when the Bluetooth connect starts a moment later.
        val found = linkedMapOf<String, BssidHelperProtocol.Sighting.Peer>()
        val deadline = System.currentTimeMillis() + DISCOVERY_MS
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(DISCOVERY_POLL_MS)
            requestPeers(manager, channel).forEach { found[it.address] = it }
        }
        stopDiscovery(manager, channel)

        val note = if (found.isEmpty()) "Discovery ran, no peers answered." else ""
        return found.values.toList() to note
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestPeers(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ): List<BssidHelperProtocol.Sighting.Peer> = suspendCancellableCoroutine { cont ->
        manager.requestPeers(channel) { list: WifiP2pDeviceList ->
            val peers = list.deviceList.map { device ->
                BssidHelperProtocol.Sighting.Peer(
                    deviceName = device.deviceName ?: "",
                    address = device.deviceAddress ?: "",
                    isGroupOwner = device.isGroupOwner
                )
            }.filter { it.address.isNotEmpty() }
            if (cont.isActive) cont.resume(peers)
        }
    }

    /**
     * Stops discovery and waits for the framework to confirm it.
     *
     * Fire-and-forget was wrong here. The very next thing the rider does is hand the address over
     * by Bluetooth, and starting that while Wi-Fi is still sweeping channels puts the two radios in
     * each other's way on a combo chip. Waiting costs a moment and removes the overlap entirely.
     */
    @SuppressLint("MissingPermission")
    private suspend fun stopDiscovery(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        withTimeoutOrNull(STOP_DISCOVERY_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                try {
                    manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                        override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(Unit) }
                    })
                } catch (e: Exception) {
                    // The framework times discovery out on its own; nothing else to do.
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private fun p2pError(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "not supported on this phone"
        WifiP2pManager.BUSY -> "the framework is busy (is Android Auto connected?)"
        WifiP2pManager.ERROR -> "internal error"
        else -> "reason $reason"
    }

    private companion object {
        const val SCAN_SWEEPS = 3
        const val SCAN_SWEEP_TIMEOUT_MS = 12_000L
        const val DISCOVERY_MS = 12_000L
        const val DISCOVERY_POLL_MS = 2_000L
        const val STOP_DISCOVERY_TIMEOUT_MS = 3_000L
    }
}
