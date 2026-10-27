package com.andrerinas.headunitrevived.contract

import java.util.UUID

/**
 * Wire protocol between the head unit and the phone-side helper app, whose only job is to tell the
 * head unit the BSSID of its own Wi-Fi Direct group.
 *
 * The head unit cannot read that address itself. Android masks the MAC of the P2P interface from
 * unprivileged apps from Android 10 on, and there is no way around it: the framework erases it from
 * the group info, `/sys/class/net` is closed by SELinux, and the SoftAp APIs that do expose it are
 * system-only. But the address is not secret — any other device sees it. So the phone looks, and
 * tells us.
 *
 * The helper stays deliberately stupid: it reports everything it saw, by both of the routes it has,
 * and lets the head unit recognise its own. Only the head unit knows its own SSID and its own P2P
 * device name, so only the head unit can do the matching — and keeping it that way means the helper
 * never needs updating when the naming changes.
 *
 * Two kinds of sighting, because they fail independently and we do not yet know which one works on
 * which phone:
 *
 *  - `AP` — an ordinary Wi-Fi scan result. This is the address we actually want: the BSSID of the
 *    group's access point, which is exactly what the handshake has to hand to the phone.
 *  - `P2P` — a Wi-Fi Direct peer found by [android.net.wifi.p2p.WifiP2pManager.discoverPeers].
 *    Peer addresses are not masked (only a device's *own* address is), and this route is not
 *    subject to Wi-Fi scan throttling. Whether a peer's device address is the same thing as the
 *    group's BSSID is device-dependent, so it is reported separately and treated as the fallback.
 *
 * Text rather than protobuf: this runs once in a blue moon on a link that is already up, so the
 * bytes do not matter, and being able to read a session out of a log does.
 *
 *     ->  CEON-BSSID/2
 *     ->  AP <ssid>\t<bssid>                            (one per scan result, may be none)
 *     ->  P2P <deviceName>\t<address>\t<isGroupOwner>   (one per discovered peer, may be none)
 *     ->  END
 *     <-  OK <source> <bssid>   (matched and stored; source is AP or P2P)
 *     <-  NO_MATCH              (nothing the head unit recognised)
 *
 * Lines are UTF-8, separated by '\n'. Unknown line prefixes are ignored rather than rejected, so a
 * newer helper talking to an older head unit degrades instead of failing.
 */
object BssidHelperProtocol {

    /**
     * RFCOMM service the head unit advertises for the helper. Randomly generated for this purpose —
     * it must not collide with the Android Auto or Hands-Free UUIDs the head unit also serves.
     */
    val SERVICE_UUID: UUID = UUID.fromString("b7d1f2c4-6a3e-4c85-9f0b-2e7a41d8c530")

    /** Name attached to the SDP record, so the service is recognisable in a scan. */
    const val SERVICE_NAME = "CEONIX Headunit BSSID Helper"

    /**
     * First line the helper sends. Bumped only if the format changes incompatibly.
     *
     * The `CEON` in here is a wire token, not the product name — the head unit compares it for
     * equality and drops the connection on anything else, so it deliberately survived the renames to
     * CEONIX Ride and CEONIX Headunit. Renaming it would break every helper APK already on a phone. See
     * docs/BRANDING_MAP.md B4.
     */
    const val HELLO = "CEON-BSSID/2"

    /** Accepted as well as [HELLO]: v1 helpers only ever sent bare `AP`-shaped lines. */
    const val HELLO_V1 = "CEON-BSSID/1"

    /** Terminates the list of sightings. */
    const val END = "END"

    /** Reply prefix on success; followed by the source tag and the address that was stored. */
    const val REPLY_OK = "OK"

    /** Reply when none of the reported sightings is one the head unit recognises. */
    const val REPLY_NO_MATCH = "NO_MATCH"

    /** Prefix every Wi-Fi Direct group SSID carries, per the Wi-Fi Direct specification. */
    const val DIRECT_PREFIX = "DIRECT-"

    /** Suffix this head unit appends to the SSIDs it generates. See [isLikelyHeadUnitSsid]. */
    const val SSID_SUFFIX = "CEONIX"

    const val TAG_AP = "AP"
    const val TAG_P2P = "P2P"

    /** One thing the phone saw. */
    sealed class Sighting {
        /** A Wi-Fi network, from an ordinary scan. [bssid] is the address the handshake needs. */
        data class AccessPoint(val ssid: String, val bssid: String) : Sighting()

        /** A Wi-Fi Direct peer, from P2P discovery. */
        data class Peer(
            val deviceName: String,
            val address: String,
            val isGroupOwner: Boolean
        ) : Sighting()
    }

    fun encode(sighting: Sighting): String = when (sighting) {
        is Sighting.AccessPoint -> "$TAG_AP ${sighting.ssid}\t${sighting.bssid}"
        is Sighting.Peer ->
            "$TAG_P2P ${sighting.deviceName}\t${sighting.address}\t${sighting.isGroupOwner}"
    }

    /**
     * Parses one reported sighting, or null if the line is malformed or of a kind we do not know.
     *
     * Untagged lines are read as v1 access-point entries, so a v1 helper still works.
     */
    fun decode(line: String): Sighting? {
        // Only strip a tag we actually recognise. SSIDs may contain spaces, so splitting on the
        // first space unconditionally would mangle the untagged v1 form.
        val isPeer = line.startsWith("$TAG_P2P ")
        val body = when {
            isPeer -> line.removePrefix("$TAG_P2P ")
            line.startsWith("$TAG_AP ") -> line.removePrefix("$TAG_AP ")
            else -> line
        }
        val parts = body.split('\t')
        if (parts.size < 2) return null

        return if (isPeer) {
            val address = parts[1]
            if (address.isEmpty()) null
            else Sighting.Peer(parts[0], address, parts.getOrNull(2)?.toBoolean() ?: false)
        } else {
            val ssid = parts[0]
            val bssid = parts[1]
            if (ssid.isEmpty() || bssid.isEmpty()) null
            else Sighting.AccessPoint(ssid, bssid)
        }
    }

    /**
     * Whether an SSID looks like one this head unit generated.
     *
     * Only a hint, and only used as a fallback when the head unit does not currently know its own
     * SSID — the group name is chosen by the framework rather than by us on the fallback creation
     * paths, so a negative answer proves nothing.
     */
    fun isLikelyHeadUnitSsid(ssid: String): Boolean =
        ssid.startsWith(DIRECT_PREFIX) && ssid.endsWith(SSID_SUFFIX)

    /** True for the placeholder addresses Android hands out instead of a real MAC. */
    fun isMasked(address: String): Boolean {
        val normalised = address.lowercase()
        return normalised.isEmpty() ||
            normalised == "00:00:00:00:00:00" ||
            normalised == "02:00:00:00:00:00"
    }
}
