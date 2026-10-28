package com.ceonix.licensing

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * "Is there internet right now", and "tell me once when there is".
 *
 * Used to time online checks so they are attempted when they can succeed, instead of on a fixed
 * schedule. Needs ACCESS_NETWORK_STATE, which the host app declares.
 */
class NetworkWatch(context: Context) {
    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Suppress("DEPRECATION")
    fun hasInternet(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 23) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            cm.activeNetworkInfo?.isConnected == true
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Runs [onAvailable] on the main thread the first time a network with internet appears, then
     * stops listening. A second call replaces the first.
     */
    fun awaitInternet(onAvailable: () -> Unit) {
        cancel()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Give validation a moment; the Play Store needs a working route, not just a link.
                main.postDelayed({
                    if (callback === this) {
                        cancel()
                        onAvailable()
                    }
                }, SETTLE_MS)
            }
        }
        callback = cb
        try {
            cm.registerNetworkCallback(request, cb)
        } catch (e: Exception) {
            callback = null
        }
    }

    fun cancel() {
        val cb = callback ?: return
        callback = null
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // Already gone.
        }
    }

    private companion object {
        const val SETTLE_MS = 3_000L
    }
}
