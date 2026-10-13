package com.andrerinas.headunitrevived.utils

import android.content.Context

/**
 * Play Store counterpart of the GitHub flavour's VpnControl.
 *
 * Google Play only allows VpnService in apps whose core function is a VPN, so this flavour
 * ships without DummyVpnService. Callers already handle isVpnAvailable() == false: offline
 * Self Mode simply runs without the dummy tunnel.
 */
object VpnControl {
    fun startVpn(context: Context) {
        AppLog.i("VpnControl: no VPN service in the Play Store build, nothing to start")
    }

    fun stopVpn(context: Context) {
        // Nothing was started, nothing to stop.
    }

    fun isVpnAvailable(): Boolean = false
}
