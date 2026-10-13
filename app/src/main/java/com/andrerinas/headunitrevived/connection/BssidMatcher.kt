package com.andrerinas.headunitrevived.connection

import com.andrerinas.headunitrevived.contract.BssidHelperProtocol
import com.andrerinas.headunitrevived.contract.BssidHelperProtocol.Sighting

/**
 * Picks our own Wi-Fi Direct group out of everything the phone-side helper saw.
 *
 * This has to happen here rather than on the phone: the helper has no idea which of the networks
 * around it belongs to this head unit, and we know our own SSID and our own P2P device name
 * perfectly well — it is only the *address* that Android hides from us. Keeping the helper ignorant
 * also means it never needs updating when the naming changes.
 *
 * Getting this wrong is worse than getting nothing. A neighbour's address would be handed to the
 * phone as ours and the phone would fail to join with no error anywhere, which is precisely the
 * failure we are trying to end. So every rule here is an exact match or a lone unambiguous
 * candidate; nothing is guessed.
 */
object BssidMatcher {

    enum class Source { AP, P2P }

    data class Match(
        val source: Source,
        val bssid: String,
        /** What we matched on — the SSID or the device name — for the log and the screen. */
        val label: String
    )

    data class Result(
        val chosen: Match?,
        /** The runner-up, kept so a disagreement between the two routes shows up in the log. */
        val alternative: Match?
    )

    /**
     * @param ownSsid    the network name of the group we are currently hosting, if we have one.
     * @param ownP2pName our own Wi-Fi Direct device name, which Android does *not* mask.
     */
    fun match(
        sightings: List<Sighting>,
        ownSsid: String?,
        ownP2pName: String?
    ): Result {
        val fromScan = matchAccessPoint(sightings.filterIsInstance<Sighting.AccessPoint>(), ownSsid)
        val fromPeers = matchPeer(sightings.filterIsInstance<Sighting.Peer>(), ownP2pName)

        // The scan result wins when both are available: its BSSID is by definition the address of
        // the group's access point, which is exactly what the handshake has to send. A peer's
        // device address is usually the same thing, but that is a device-dependent coincidence
        // rather than a promise.
        return if (fromScan != null) Result(fromScan, fromPeers)
        else Result(fromPeers, null)
    }

    private fun matchAccessPoint(
        networks: List<Sighting.AccessPoint>,
        ownSsid: String?
    ): Match? {
        val usable = networks.filter { !BssidHelperProtocol.isMasked(it.bssid) }

        val exact = ownSsid?.let { ssid -> usable.firstOrNull { it.ssid == ssid } }
        if (exact != null) return Match(Source.AP, exact.bssid, exact.ssid)

        // No group of ours is up, so there is no SSID to match against. Fall back to the naming
        // convention, but only when it is unambiguous.
        val byConvention = usable
            .filter { BssidHelperProtocol.isLikelyHeadUnitSsid(it.ssid) }
            .takeIf { it.size == 1 }
            ?.first()
            ?: return null
        return Match(Source.AP, byConvention.bssid, byConvention.ssid)
    }

    private fun matchPeer(peers: List<Sighting.Peer>, ownP2pName: String?): Match? {
        if (ownP2pName.isNullOrEmpty()) return null
        val candidates = peers
            .filter { !BssidHelperProtocol.isMasked(it.address) && it.deviceName == ownP2pName }
        if (candidates.isEmpty()) return null

        // Two devices sharing a name is unlikely but not impossible; being the group owner is what
        // we actually are, so it breaks the tie.
        val peer = candidates.firstOrNull { it.isGroupOwner } ?: candidates.first()
        return Match(Source.P2P, peer.address, peer.deviceName)
    }
}
