package com.andrerinas.headunitrevived.license

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GitHub (sideload / self-built) counterpart of the Play flavour's LicenseGuard.
 *
 * The source is public under the AGPL and anyone may build and run it; only the copy sold through
 * Google Play checks a licence. Every gate therefore opens here, and nothing talks to the Play Store.
 */
object LicenseGuard {
    const val enforced = false

    private val _status = MutableStateFlow(LicenseStatus.LICENSED)
    val status: StateFlow<LicenseStatus> = _status.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    fun init(context: Context) {
        // Nothing to check.
    }

    fun isUsable(context: Context): Boolean = true

    fun verifyNow(context: Context) {
        // Nothing to check.
    }

    fun verifiedAtMillis(context: Context): Long? = null
}
