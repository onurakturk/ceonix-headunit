package com.andrerinas.headunitrevived.license

import android.content.Context
import android.content.Intent
import com.andrerinas.headunitrevived.BuildConfig
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.utils.AppLog
import com.ceonix.licensing.LicenseConfig
import com.ceonix.licensing.LicenseGate
import com.ceonix.licensing.LicenseState
import com.ceonix.licensing.NetworkWatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Play build's licence: checked once online, then valid on this device for good.
 *
 * The head unit is offline by design, so the timing is opportunistic rather than scheduled:
 *  - first launch (or after cleared data): ask the Play Store as soon as there is internet, and
 *    keep the Android Auto and Ride features closed until it has answered LICENSED;
 *  - licensed: if the internet happens to be there during a run, ask once more, quietly. Only a
 *    signed NOT_LICENSED takes the licence away (refund); no network or an error changes nothing.
 *
 * The gates call [isUsable] themselves (AapService, App, Ride) instead of reading one cached flag,
 * and each call re-verifies the stored, Google-signed answer — see the licensing module.
 *
 * Every decision is logged with the `LICENSE:` prefix.
 */
object LicenseGuard {
    const val enforced = true

    private val _status = MutableStateFlow(LicenseStatus.UNVERIFIED)
    val status: StateFlow<LicenseStatus> = _status.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    @Volatile private var gate: LicenseGate? = null
    @Volatile private var watch: NetworkWatch? = null
    @Volatile private var initialised = false
    @Volatile private var refreshedThisRun = false

    @Synchronized
    private fun gate(context: Context): LicenseGate = gate ?: LicenseGate(
        context,
        LicenseConfig(
            publicKeyBase64 = LicenseKeys.publicKey(),
            allowedCertSha256 = if (BuildConfig.DEBUG) {
                LicenseKeys.ALLOWED_CERTS + LicenseKeys.DEBUG_CERT
            } else {
                LicenseKeys.ALLOWED_CERTS
            },
            log = { AppLog.i("LICENSE: $it") },
        )
    ).also { gate = it }

    private fun watch(context: Context): NetworkWatch =
        watch ?: NetworkWatch(context).also { watch = it }

    /** Once per process, from App.onCreate. */
    fun init(context: Context) {
        if (initialised) return
        initialised = true
        val state = evaluate(context)
        AppLog.i("LICENSE: startup state $state")
        when (state) {
            LicenseStatus.UNVERIFIED -> whenOnline(context) { verifyNow(context) }
            LicenseStatus.LICENSED -> whenOnline(context) { quietRefresh(context) }
            // A signed refusal or a re-signed APK: nothing the network could change on its own.
            LicenseStatus.NOT_LICENSED, LicenseStatus.TAMPERED -> Unit
        }
    }

    fun isUsable(context: Context): Boolean = evaluate(context) == LicenseStatus.LICENSED

    /** Ask the Play Store now (the lock screen's button, or the first internet of a run). */
    fun verifyNow(context: Context) {
        val app = context.applicationContext
        val before = _status.value
        _checking.value = true
        gate(app).verifyOnline { result ->
            _checking.value = false
            val after = publish(result)
            if (after == LicenseStatus.LICENSED && before != LicenseStatus.LICENSED) {
                AppLog.i("LICENSE: unlocked — starting the Android Auto host")
                startWireless(app)
            }
            // Still waiting and offline: try again by itself the moment a network shows up, so a
            // rider who connects to Wi-Fi from the lock screen does not have to press anything.
            if (after == LicenseStatus.UNVERIFIED && !watch(app).hasInternet()) {
                whenOnline(app) { verifyNow(app) }
            }
        }
    }

    fun verifiedAtMillis(context: Context): Long? = gate(context).verifiedAtMillis()

    private fun quietRefresh(context: Context) {
        if (refreshedThisRun) return
        refreshedThisRun = true
        AppLog.i("LICENSE: internet available, refreshing quietly")
        verifyNow(context)
    }

    private fun whenOnline(context: Context, action: () -> Unit) {
        val w = watch(context)
        if (w.hasInternet()) action() else w.awaitInternet(action)
    }

    private fun evaluate(context: Context): LicenseStatus = publish(gate(context).evaluate())

    private fun publish(state: LicenseState): LicenseStatus {
        val status = when (state) {
            LicenseState.LICENSED -> LicenseStatus.LICENSED
            LicenseState.UNVERIFIED -> LicenseStatus.UNVERIFIED
            LicenseState.NOT_LICENSED -> LicenseStatus.NOT_LICENSED
            LicenseState.TAMPERED -> LicenseStatus.TAMPERED
        }
        _status.value = status
        return status
    }

    private fun startWireless(context: Context) {
        try {
            context.startService(
                Intent(context, AapService::class.java).setAction(AapService.ACTION_START_WIRELESS)
            )
        } catch (e: Exception) {
            // Background start refused; the next launch or screen-on brings the host up anyway.
            AppLog.w("LICENSE: could not start the Android Auto host: ${e.message}")
        }
    }
}
